package no.nav.fager.maskinporten

import arrow.core.Either
import arrow.core.raise.either
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.api.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.micrometer.core.instrument.Gauge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.fager.infrastruktur.Metrics
import no.nav.fager.infrastruktur.RequiresReady
import no.nav.fager.infrastruktur.logger
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


class MaskinportenConfig(
    val clientId: String,
    val clientJwk: String,
    val issuer: String,
    val tokenEndpoint: String,
) {
    /** OBS: Verdien [clientJwk] er en *secret*. Pass på at den ikke blir logget! */
    override fun toString() =
        "Maskinporten(clientId: $clientId, clientJwk: SECRET, issuer: $issuer, tokenEndpoint: $tokenEndpoint)"

    companion object {
        fun nais() = MaskinportenConfig(
            clientId = System.getenv("MASKINPORTEN_CLIENT_ID"),
            clientJwk = System.getenv("MASKINPORTEN_CLIENT_JWK"),
            issuer = System.getenv("MASKINPORTEN_ISSUER"),
            tokenEndpoint = System.getenv("MASKINPORTEN_TOKEN_ENDPOINT"),
        )
    }
}

class MaskinportenPluginConfig(
    var maskinporten: Maskinporten? = null,
)

val MaskinportenPlugin = createClientPlugin("MaskinportenPlugin", ::MaskinportenPluginConfig) {
    val maskinporten = requireNotNull(pluginConfig.maskinporten) {
        "MaskinportenPlugin: property 'maskinporten' must be set in configuration when installing plugin"
    }

    onRequest { request, _ ->
        request.bearerAuth(maskinporten.accessToken())
    }
}

@OptIn(ExperimentalTime::class)
class Maskinporten(
    private val maskinportenConfig: MaskinportenConfig,

    private val scope: String,

    backgroundCoroutineScope: CoroutineScope?,

    /** Kun nødvendig for test-kode. */
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : RequiresReady {
    private val log = logger()

    /* Implementasjon basert på nais-doc:
     * https://doc.nais.io/auth/maskinporten/how-to/consume/#acquire-token
     */
    private val rsaKey = RSAKey.parse(maskinportenConfig.clientJwk)
    private val signer = RSASSASigner(rsaKey.toPrivateKey())
    private val header: JWSHeader = JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID(rsaKey.keyID)
        .type(JOSEObjectType.JWT)
        .build()

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    inner class Cache(
        val accessToken: String,
        val expiresAt: ComparableTimeMark,
    ) {
        fun expiresIn(): Duration = expiresAt - timeSource.markNow()
    }

    private val cache = AtomicReference<Cache>(null)

    private val expiresInGauge =
        Gauge.builder("maskinporten.token.expiry.seconds", cache) {
            it.get()?.expiresIn()?.toDouble(DurationUnit.SECONDS) ?: -1.0
        }.tag("scope", scope).register(Metrics.meterRegistry)


    private val refreshThreshold = 10.minutes

    init {
        backgroundCoroutineScope?.launch {
            while (true) {
                val (error, currentToken) = refreshTokenIfNeeded()
                expiresInGauge.value() // Trigger gauge update
                when {
                    currentToken == null -> {
                        log.info("token med scope={} mangler. forsøk på å hente nytt feilet.", scope, error)
                        /* Prøv å refreshe ganske aggresivt når vi starter opp. */
                        delay(1.seconds)
                    }

                    currentToken.expiresIn() < 0.seconds -> {
                        log.error("token med scope={} er utløpt. forsøk på å hente nytt feilet.", scope, error)
                        /* Nå har det feilet lenge, så det er ikke stort poeng i å prøve
                         * såpass ofte at vi legger unødvendig last på maskinporten. */
                        delay(10.seconds)
                    }

                    currentToken.expiresIn() < refreshThreshold -> {
                        log.info(
                            "token med scope={} expires_in={}. forsøk på å hente nytt feilet.",
                            scope,
                            currentToken.expiresIn().toIsoString(),
                            error
                        )
                        /* Hvis vi er her så har det feilet minst en gang å prøve å refreshe tokenet.
                        * Vi har ganske god tid på oss (se [refreshThreshold]), så vi fortsetter bare
                        * å spørre i et jevnt tempo. */
                        delay(10.seconds)
                    }

                    else -> {
                        log.info(
                            "token med scope={} expires_in={}. standing by.",
                            scope,
                            currentToken.expiresIn().toIsoString()
                        )
                        delay(1.minutes)
                    }
                }
            }
        }
    }

    suspend fun refreshTokenIfNeeded(): Pair<Throwable?, Cache?> {
        val cacheSnapshot = cache.get()
        return if (cacheSnapshot == null || cacheSnapshot.expiresIn() < refreshThreshold) {
            when (val result = fetchToken()) {
                is Either.Right -> {
                    cache.set(result.value)
                    Pair(null, result.value)
                }

                is Either.Left -> {
                    Pair(result.value, cacheSnapshot)
                }
            }
        } else {
            Pair(null, cacheSnapshot)
        }
    }

    private suspend fun fetchToken(): Either<Throwable, Cache> = either {
        val now = Instant.now()
        val expiration = now + 1.minutes.toJavaDuration()

        val claims: JWTClaimsSet = JWTClaimsSet.Builder()
            .issuer(maskinportenConfig.clientId)
            .audience(maskinportenConfig.issuer)
            .issueTime(Date.from(now))
            .claim("scope", scope)
            .expirationTime(Date.from(expiration))
            .jwtID(UUID.randomUUID().toString())
            .build()

        val clientAssertion = SignedJWT(header, claims)
            .apply { sign(signer) }
            .serialize()

        val httpResponse = httpClient.submitForm(
            url = maskinportenConfig.tokenEndpoint,
            formParameters = parameters {
                append("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                append("assertion", clientAssertion)
            }
        ) {
            accept(ContentType.Application.Json)
        }

        if (!httpResponse.status.isSuccess()) {
            /* Ikke error siden det er retrylogikk. For alerts, så lager vi heller metric på expiration time. */
            raise(
                RuntimeException(
                    "request for token from maskinporten failed with http status ${httpResponse.status}: ${httpResponse.bodyAsText()}"
                )
            )
        }
        val body = httpResponse.body<TokenEndpointResponse>()

        Cache(
            accessToken = body.accessToken,
            expiresAt = timeSource.markNow() + body.expiresIn.seconds,
        )
    }


    fun accessToken(): String {
        return requireNotNull(cache.get()?.accessToken) {
            """
                Maskinporten is not ready yet. Did you forget to connect Maskinporten::isReady into
                the k8s' ready-endpoint?
           """.trimIndent()
        }
    }

    override fun isReady() = cache.get() != null
}

@Serializable
class TokenEndpointResponse(
    @SerialName("access_token")
    val accessToken: String,

    @SerialName("expires_in")
    val expiresIn: Long,
) {
    /* NB: accessToken en en secret, gjør det vanskelig å printe med en feil. */
    override fun toString() = "TokenEndpointResponse(accessToken: SECRET, expiresIn: $expiresIn)"
}

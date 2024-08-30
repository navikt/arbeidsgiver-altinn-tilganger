package no.nav.fager

import arrow.core.Either
import arrow.core.raise.either
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.*
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration


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
) {
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

    @Volatile
    private var cache: Cache? = null

    val isReady: Boolean get() = cache != null

    init {
        backgroundCoroutineScope?.launch {
            while (true) {
                refreshTokenIfNeeded()
                delay(5.seconds)
            }
        }
    }

    suspend fun refreshTokenIfNeeded() {
        val cacheSnapshot = cache
        if (cacheSnapshot == null || cacheSnapshot.expiresIn() < 5.minutes) {
            when (val result = fetchToken()) {
                is Either.Right -> {
                    cache = result.value
                    log.info(
                        "hentet nytt token med scope={} expires_in={}",
                        scope,
                        result.value.expiresIn().toIsoString()
                    )
                }

                is Either.Left -> {
                    log.info(
                        "hente nytt token for scope={} feilet med exception={}, gammelt expires_in={}",
                        scope,
                        result.value.javaClass.canonicalName,
                        cacheSnapshot?.expiresIn()?.toIsoString(),
                        result.value
                    )
                }
            }
        } else {
            log.info("nothing to do for scope={} expires_in={}", scope, cacheSnapshot.expiresIn().toIsoString())
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

        log.info("hentet token: scope=$scope expiresIn=${body.expiresIn}s")

        Cache(
            accessToken = body.accessToken,
            expiresAt = timeSource.markNow() + body.expiresIn.seconds,
        )
    }


    fun accessToken(): String {
        return requireNotNull(cache?.accessToken) {
            """
                Maskinporten is not ready yet. Did you forget to connect Maskinporten::isReady into
                the k8s' ready-endpoint?
           """.trimIndent()
        }
    }
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

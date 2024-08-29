package no.nav.fager

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.time.delay
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
    var maskinportenConfig: MaskinportenConfig? = null,
    var scope: String? = null,
)

/** Husk å close clienten når du er ferdig med den! Ellers lekker det ressurser. */
@OptIn(ExperimentalTime::class)
val MaskinportenPlugin = createClientPlugin("MaskinportenPlugin", ::MaskinportenPluginConfig) {
    val maskinporten = pluginConfig.run {
        val maskinporten = this.maskinporten
        val maskinportenConfig = this.maskinportenConfig
        val scope = this.scope

        if (maskinporten != null && maskinportenConfig == null && scope == null)
            maskinporten
        else if (maskinporten == null && maskinportenConfig != null && scope != null) {
            Maskinporten(maskinportenConfig, scope).also {
                onClose {
                    runBlocking {
                        it.close()
                    }
                }
                it.start()
            }
        } else {
            error("Enten må `maskinporten` være definert, eller `maskinportenConfig` og `scope`.")
        }
    }

    onRequest { request, _ ->
        request.bearerAuth(maskinporten.accessToken())
    }
}

@OptIn(ExperimentalTime::class)
class Maskinporten(
    private val maskinportenConfig: MaskinportenConfig,

    private val scope: String,

    /** Kun nødvendig for test-kode. */
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,

    /** Kun nødvendig for test-kode. */
    @OptIn(DelicateCoroutinesApi::class)
    private val coroutineScope: CoroutineScope = GlobalScope,

    /** Injection kun nødvendig for test-kode. */
    httpClientEngine: HttpClientEngine = CIO.create(),
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

    private val httpClient = HttpClient(httpClientEngine) {
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

    private var refreshJob =
        coroutineScope.launch(CoroutineName("maksinporten-refresh-job"), start = CoroutineStart.LAZY) {
            while (true) {
                refreshTokenIfNeeded()
                delay(10.seconds.toJavaDuration())
            }
        }

    suspend fun refreshTokenIfNeeded() {
        val snapshot = cache
        if (snapshot == null || snapshot.expiresIn() < 5.minutes) {
            val newToken = fetchToken()
            if (newToken != null) {
                cache = newToken
            }
        }
    }

    fun accessToken(): String {
        runBlocking { refreshTokenIfNeeded() }
        return requireNotNull(cache?.accessToken) {
            """
                Maskinporten is not ready yet. Did you forget to connect Maskinporten::isReady into
                the k8s' ready-endpoint?
           """.trimIndent()
        }
    }

    fun start() {
        refreshJob.start()
    }

    suspend fun close() {
        refreshJob.cancelAndJoin()
    }

    private suspend fun fetchToken(): Cache? {
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
            /* Ikke error siden det er retrylogikk. */
            log.info("request for token from maskinporten failed with http status {}", httpResponse.status)
            return null
        }
        val body = httpResponse.body<TokenEndpointResponse>()

        log.info("hentet token: scope=$scope expiresIn=${body.expiresIn}s")

        return Cache(
            accessToken = body.accessToken,
            expiresAt = timeSource.markNow() + body.expiresIn.seconds,
        )
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

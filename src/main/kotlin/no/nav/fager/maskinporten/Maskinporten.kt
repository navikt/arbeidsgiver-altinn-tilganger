package no.nav.fager.maskinporten

import arrow.core.Either
import arrow.core.raise.either
import io.ktor.client.plugins.api.*
import io.ktor.client.request.*
import io.micrometer.core.instrument.Gauge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.nav.fager.infrastruktur.*
import no.nav.fager.texas.AuthClient
import no.nav.fager.texas.IdentityProvider
import no.nav.fager.texas.TexasAuthConfig
import no.nav.fager.texas.TokenResponse
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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
    texasAuthConfig: TexasAuthConfig,

    private val scope: String,

    backgroundCoroutineScope: CoroutineScope?,

    /** Kun nødvendig for test-kode. */
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : RequiresReady {
    private val log = logger()

    val maskinporten = AuthClient(texasAuthConfig, IdentityProvider.MASKINPORTEN)

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
        when (val response = maskinporten.token(scope)) {
            is TokenResponse.Success -> Cache(
                accessToken = response.accessToken,
                expiresAt = timeSource.markNow() + response.expiresInSeconds.seconds,
            )

            is TokenResponse.Error -> raise(
                RuntimeException(
                    "request for token from maskinporten failed with http status ${response.status}: ${response.error}"
                )
            )
        }
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

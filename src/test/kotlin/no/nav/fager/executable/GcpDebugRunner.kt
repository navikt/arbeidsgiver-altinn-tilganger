package no.nav.fager.executable

import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.valkey.DefaultJedisClientConfig
import io.valkey.HostAndPort
import io.valkey.JedisPool
import io.valkey.JedisPoolConfig
import kotlinx.coroutines.runBlocking
import no.nav.fager.DevGcpEnv
import no.nav.fager.altinn.Altinn2ClientImpl
import no.nav.fager.altinn.Altinn2Config
import no.nav.fager.altinn.Altinn3ClientImpl
import no.nav.fager.altinn.Altinn3Config
import no.nav.fager.altinn.AltinnService
import no.nav.fager.altinn.ResourceRegistry
import no.nav.fager.backgroundScope
import no.nav.fager.redis.AltinnTilgangerRedisClientImpl
import no.nav.fager.redis.RedisConfig
import no.nav.fager.texas.TexasAuthConfig
import java.net.URI


fun main() = runBlocking {
    val gcpEnv = DevGcpEnv()
//    val gcpEnv = ProdGcpEnv()
    val texasEnv = gcpEnv.getEnvVars("NAIS_TOKEN_")
    URI(texasEnv["NAIS_TOKEN_ENDPOINT"]!!).let { uri ->
        try {
            uri.toURL().openConnection().connect()
            println("""texas is available at $uri""")
        } catch (e: Exception) {
            println("")

            println(
                """
        ######
        # Failed to connect to $uri - ${e.message}
        # 
        # Connecting to altinn 3 via devgcp requires port forwarding for texas.
        #
        # E.g: kubectl port-forward ${gcpEnv.getPods().first()} ${uri.port}
        ######
        
        An attempt at port forward will be made for you now:
        
            """.trimIndent()
            )

            gcpEnv.portForward(uri.port) {
                try {
                    uri.toURL().openConnection().connect()
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
    }

    val redis = gcpEnv.getSecrets(gcpEnv.findSecret("aiven-valkey-tilganger"))
    val altinnTilganger = gcpEnv.getSecrets("altinn-tilganger")

    val altinn3Config = Altinn3Config(
        baseUrl = "https://platform.tt02.altinn.no",
        ocpApimSubscriptionKey = altinnTilganger["OCP_APIM_SUBSCRIPTION_KEY"]!!,
    )
    val altinn2Config = Altinn2Config(
        baseUrl = "https://tt02.altinn.no",
        apiKey = altinnTilganger["ALTINN_2_API_KEY"]!!,
    )
    val texasAuthConfig = TexasAuthConfig(
        tokenEndpoint = texasEnv["NAIS_TOKEN_ENDPOINT"]!!,
        tokenExchangeEndpoint = texasEnv["NAIS_TOKEN_EXCHANGE_ENDPOINT"]!!,
        tokenIntrospectionEndpoint = texasEnv["NAIS_TOKEN_INTROSPECTION_ENDPOINT"]!!,
    )
    val redisConfig = RedisConfig(
        JedisPool(
            JedisPoolConfig(),
            HostAndPort(
                redis["VALKEY_HOST_TILGANGER"]!!,
                redis["VALKEY_PORT_TILGANGER"]!!.toInt()
            ),
            DefaultJedisClientConfig.builder()
                .user(redis["VALKEY_USERNAME_TILGANGER"]!!)
                .password(redis["VALKEY_PASSWORD_TILGANGER"]!!)
                .ssl(true)
                .build()
        )
    )

    val altinnService = AltinnService(
        altinn2Client = Altinn2ClientImpl(
            altinn2Config = altinn2Config,
            texasAuthConfig = texasAuthConfig,
        ) {
            install(Logging) {
                level = LogLevel.BODY
            }
        },
        altinn3Client = Altinn3ClientImpl(
            altinn3Config = altinn3Config,
            texasAuthConfig = texasAuthConfig,
        ) {
            install(Logging) {
                level = LogLevel.BODY
            }
        },
        resourceRegistry = ResourceRegistry(
            altinn3Client = Altinn3ClientImpl(
                altinn3Config = altinn3Config,
                texasAuthConfig = texasAuthConfig,
            ),
            redisConfig = redisConfig,
            backgroundCoroutineScope = backgroundScope
        ),
        redisClient = AltinnTilgangerRedisClientImpl(redisConfig),
    )

    // 16878397960, 12918998479, 25899099616, 22810699640
    altinnService.hentTilgangerFraAltinn("16878397960")
//    altinnService.hentTilgangerFraAltinn("12918998479")
//    altinnService.hentTilgangerFraAltinn("25899099616")
//    altinnService.hentTilgangerFraAltinn("22810699640")

    //embeddedServer(CIO, port = 8080, host = "0.0.0.0", module = {
    //    ktorConfig(
    //        altinn3Config = altinn3Config,
    //        altinn2Config = altinn2Config,
    //        texasAuthConfig = texasAuthConfig,
    //        redisConfig = redisConfig,
    //    )
    //}).start(wait = true)

    Unit
}


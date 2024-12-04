package no.nav.fager

//import io.github.smiley4.ktorswaggerui.dsl.routing.get
//import io.github.smiley4.ktorswaggerui.dsl.routing.post
//import io.github.smiley4.ktorswaggerui.routing.openApiSpec
//import io.github.smiley4.ktorswaggerui.routing.swaggerUI
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.logging.LogbackMetrics
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.nav.fager.AltinnTilgangerResponse.Companion.toResponse
import no.nav.fager.altinn.*
//import no.nav.fager.doc.swaggerDocumentation
import no.nav.fager.infrastruktur.*
import no.nav.fager.redis.AltinnTilgangerRedisClientImpl
import no.nav.fager.redis.RedisConfig
import no.nav.fager.texas.AuthClient
import no.nav.fager.texas.IdentityProvider
import no.nav.fager.texas.TexasAuth
import no.nav.fager.texas.TexasAuthConfig
import org.slf4j.event.Level
import java.util.*
import java.util.concurrent.ConcurrentHashMap

fun main() {
    embeddedServer(CIO, port = 8080, host = "0.0.0.0", module = {
        ktorConfig(
            altinn3Config = Altinn3Config.nais(),
            altinn2Config = Altinn2Config.nais(),
            texasAuthConfig = TexasAuthConfig.nais(),
            redisConfig = RedisConfig.nais(),
        )
    })
        .start(wait = true)
}

fun Application.ktorConfig(
    altinn3Config: Altinn3Config,
    altinn2Config: Altinn2Config,
    texasAuthConfig: TexasAuthConfig,
    redisConfig: RedisConfig,
) {
    val log = logger()

    log.info(SECURE, "Secure logging enabled")

    install(Compression) {
        gzip {
            priority = 1.0
        }
        deflate {
            priority = 10.0
            minimumSize(1024) // condition
        }
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("Unexpected exception at ktor-toplevel: {}", cause.javaClass.canonicalName, cause)
            call.respond(HttpStatusCode.InternalServerError)
        }
    }

    install(CallLogging) {
        level = Level.INFO
        filter { call -> !call.request.path().startsWith("/internal/") }
        System.getenv("NAIS_CLUSTER_NAME")?.let {
            disableDefaultColors()
        }
        mdc("method") { call ->
            call.request.httpMethod.value
        }
        mdc("host") { call ->
            call.request.header("host")
        }
        mdc("path") { call ->
            call.request.path()
        }
        mdc("clientId") { call ->
            call.principal<InnloggetBrukerPrincipal>()?.clientId
        }
        callIdMdc("x_correlation_id")
    }

    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        retrieveFromHeader(HttpHeaders.XCorrelationId)
        retrieveFromHeader("call-id")
        retrieveFromHeader("callId")
        retrieveFromHeader("call_id")

        generate {
            UUID.randomUUID().toString()
        }

        replyToHeader(HttpHeaders.XCorrelationId)
    }

    install(MicrometerMetrics) {
        registry = Metrics.meterRegistry
        distributionStatisticConfig = DistributionStatisticConfig.Builder()
            .percentilesHistogram(true)
            .build()
        meterBinders = listOf(
            ClassLoaderMetrics(),
            JvmMemoryMetrics(),
            JvmGcMetrics(),
            ProcessorMetrics(),
            JvmThreadMetrics(),
            FileDescriptorMetrics(),
            LogbackMetrics()
        )
    }

    install(ContentNegotiation) {
        json()
    }

    //swaggerDocumentation()

    val altinn2Client = Altinn2ClientImpl(
        altinn2Config = altinn2Config,
        texasAuthConfig = texasAuthConfig,
    )

    val altinn3Client = Altinn3ClientImpl(
        altinn3Config = altinn3Config,
        texasAuthConfig = texasAuthConfig,
    )

    val resourceRegistry = ResourceRegistry(
        altinn3Client = altinn3Client,
        redisConfig = redisConfig,
        backgroundCoroutineScope = this
    ).also { Health.register(it) }

    val altinnService = AltinnService(
        altinn2Client = altinn2Client,
        altinn3Client = altinn3Client,
        resourceRegistry = resourceRegistry,
        redisClient = AltinnTilgangerRedisClientImpl(redisConfig),
    )

    routing {
        route("internal") {
            get("prometheus") {
                call.respond<String>(Metrics.meterRegistry.scrape())
            }
            get("isalive") {
                call.respond(
                    if (Health.alive)
                        HttpStatusCode.OK
                    else
                        HttpStatusCode.ServiceUnavailable
                )
            }
            get("isready") {
                call.respond(
                    if (Health.ready)
                        HttpStatusCode.OK
                    else
                        HttpStatusCode.ServiceUnavailable
                )
            }
        }

//        get("/", {
//            hidden = true
//        }) {
//            call.respondRedirect("/swagger-ui/index.html")
//        }
//        route("api.json") {
//            openApiSpec()
//        }
//        route("swagger-ui") {
//            swaggerUI("/api.json")
//        }

        route("/m2m") {
            install(TexasAuth) {
                client = AuthClient(texasAuthConfig, IdentityProvider.AZURE_AD)
                validate = { AutentisertM2MPrincipal.validate(it) }
            }

            get("/whoami") {
            //    description = "Hvem er jeg autentisert som?"
            //    protected = true // må si dette eksplisitt for at swagger skal få det med seg
            //}) {
                val clientId = call.principal<AutentisertM2MPrincipal>()!!.clientId
                call.respondText(Json.encodeToString(mapOf("clientId" to clientId)))
            }

            post("/altinn-tilganger") {
//                description = "Hent tilganger fra Altinn for en bruker på fnr autentisert som entra m2m."
//                protected = true // må si dette eksplisitt for at swagger skal få det med seg
//                request {
//                    // todo document optional callid header
//                    body<AltinnTilgangerM2MRequest>()
//                }
//                response {
//                    HttpStatusCode.OK to {
//                        description = "Successful Request"
//                        body<AltinnTilgangerResponse> {
//                            exampleRef("Successful Respons", "tilganger_success")
//                        }
//                    }
//                }
//            }) {
                val clientId = call.principal<AutentisertM2MPrincipal>()!!.clientId
                val fnr = call.receive<AltinnTilgangerM2MRequest>().fnr
                withTimer(clientId).coRecord {
                    val tilganger = altinnService.hentTilganger(fnr, call)
                    call.respond(tilganger.toResponse())
                }
            }
        }

        route("/whoami") {
            // tokenx obo authentication
            install(TexasAuth) {
                client = AuthClient(texasAuthConfig, IdentityProvider.TOKEN_X)
                validate = { InnloggetBrukerPrincipal.validate(it) }
            }

            get {
                val clientId = call.principal<InnloggetBrukerPrincipal>()!!.clientId
                call.respondText(Json.encodeToString(mapOf("clientId" to clientId)))
            }
        }

        route("altinn-tilganger") {
            // tokenx obo authentication
            install(TexasAuth) {
                client = AuthClient(texasAuthConfig, IdentityProvider.TOKEN_X)
                validate = { InnloggetBrukerPrincipal.validate(it) }
            }

            post {
                val fnr = call.principal<InnloggetBrukerPrincipal>()!!.fnr
                val clientId = call.principal<InnloggetBrukerPrincipal>()!!.clientId
                withTimer(clientId).coRecord {
                    val tilganger = altinnService.hentTilganger(fnr, call)
                    call.respond(tilganger.toResponse())
                }
            }
        }
    }
}


private val clientTaggedTimerTimer = ConcurrentHashMap<String, Timer>()
private fun withTimer(clientId: String): Timer =
    clientTaggedTimerTimer.computeIfAbsent(clientId) {
        Timer.builder("altinn_tilganger_responsetid")
            .tag("klientapp", it)
            .publishPercentileHistogram()
            .register(Metrics.meterRegistry)
    }



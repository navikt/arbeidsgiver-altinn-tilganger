package no.nav.fager

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.auth.principal
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.ClosedWriteChannelException
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.logging.LogbackMetrics
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import kotlinx.serialization.json.Json
import no.nav.fager.AltinnTilgangerResponse.Companion.toResponse
import no.nav.fager.altinn.Altinn2ClientImpl
import no.nav.fager.altinn.Altinn2Config
import no.nav.fager.altinn.Altinn3ClientImpl
import no.nav.fager.altinn.Altinn3Config
import no.nav.fager.altinn.AltinnService
import no.nav.fager.altinn.ResourceRegistry
import no.nav.fager.infrastruktur.AutentisertM2MPrincipal
import no.nav.fager.infrastruktur.Health
import no.nav.fager.infrastruktur.InnloggetBrukerPrincipal
import no.nav.fager.infrastruktur.Metrics
import no.nav.fager.infrastruktur.TEAM_LOG_MARKER
import no.nav.fager.infrastruktur.coRecord
import no.nav.fager.infrastruktur.logger
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
    embeddedServer(CIO, port = 8080) {
        val log = logger()

        ktorConfig(
            altinn3Config = Altinn3Config.nais(),
            altinn2Config = Altinn2Config.nais(),
            texasAuthConfig = TexasAuthConfig.nais(),
            redisConfig = RedisConfig.nais(),
        )

        monitor.subscribe(ApplicationStopping) {
            log.info("ApplicationStopping: signal Health.terminate()")
            Health.terminate()
        }
    }.start(wait = true)
}

fun Application.ktorConfig(
    altinn3Config: Altinn3Config,
    altinn2Config: Altinn2Config,
    texasAuthConfig: TexasAuthConfig,
    redisConfig: RedisConfig,
) {
    val log = logger()

    log.info(TEAM_LOG_MARKER, "Team logging enabled")

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
            when (cause) {
                is IllegalArgumentException,
                is BadRequestException -> call.respondText(
                    text = "${HttpStatusCode.BadRequest}: $cause",
                    status = HttpStatusCode.BadRequest
                )

                is HttpRequestTimeoutException,
                is ConnectTimeoutException -> {
                    log.warn("Unexpected exception at ktor-toplevel: {}", cause.javaClass.canonicalName, cause)
                    call.response.status(HttpStatusCode.InternalServerError)
                }

                is ClosedWriteChannelException -> {
                    log.warn("Client closed connection before response was sent", cause)
                    // no response, channel already closed
                }

                else -> {
                    log.error("Unexpected exception at ktor-toplevel: {}", cause.javaClass.canonicalName, cause)
                    call.response.status(HttpStatusCode.InternalServerError)
                }
            }
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
                call.response.status(
                    if (Health.alive)
                        HttpStatusCode.OK
                    else
                        HttpStatusCode.ServiceUnavailable
                )
            }
            get("isready") {
                call.response.status(
                    if (Health.ready)
                        HttpStatusCode.OK
                    else
                        HttpStatusCode.ServiceUnavailable
                )
            }
        }

        get("/") {
            call.respondRedirect("/swagger-ui")
        }
        swaggerUI(path = "swagger-ui", swaggerFile = "openapi.yaml")

        route("/m2m") {
            install(TexasAuth) {
                client = AuthClient(texasAuthConfig, IdentityProvider.AZURE_AD)
                validate = { AutentisertM2MPrincipal.validate(it) }
            }

            get("/whoami") {
                val clientId = call.principal<AutentisertM2MPrincipal>()!!.clientId
                call.respondText(Json.encodeToString(mapOf("clientId" to clientId)))
            }

            post("/altinn-tilganger") {
                val clientId = call.principal<AutentisertM2MPrincipal>()!!.clientId
                val (fnr, filter) = call.receive<AltinnTilgangerM2MRequest>()
                withTimer(clientId).coRecord {
                    call.respond(
                        altinnService.hentTilganger(
                            fnr = fnr,
                            filter = filter,
                        ).toResponse()
                    )
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
                val filter = call.receiveText().let {
                    /**
                     * since filter is optional, and only parameter we need to support posts with empty body
                     * receive<AltinnTilgangerRequest>() will throw if body is empty,
                     * so we go via text and parse manually
                     */
                    if (it.isBlank()) {
                        Filter.empty
                    } else {
                        Json.decodeFromString(AltinnTilgangerRequest.serializer(), it).filter
                    }
                }
                withTimer(clientId).coRecord {
                    call.respond(
                        altinnService.hentTilganger(
                            fnr = fnr,
                            filter = filter,
                        ).toResponse()
                    )
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



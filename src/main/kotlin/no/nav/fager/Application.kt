package no.nav.fager

import com.auth0.jwk.JwkProviderBuilder
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.routing.openApiSpec
import io.github.smiley4.ktorswaggerui.routing.swaggerUI
import io.github.smiley4.schemakenerator.core.annotations.Description
import io.github.smiley4.schemakenerator.core.annotations.Example
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.net.URI
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.time.ExperimentalTime

fun main() {
    embeddedServer(CIO, port = 8080, host = "0.0.0.0", module = {
        ktorConfig(
            altinn3Config = Altinn3Config.nais(),
            altinn2Config = Altinn2Config.nais(),
            authConfig = AuthConfig.nais(),
            maskinportenConfig = MaskinportenConfig.nais(),
            redisConfig = RedisConfig.nais(),
        )
    })
        .start(wait = true)
}

data class AuthConfig(
    val clientId: String,
    val issuer: String,
    val jwksUri: String,
) {
    companion object {
        fun nais() = AuthConfig(
            clientId = System.getenv("TOKEN_X_CLIENT_ID"),
            issuer = System.getenv("TOKEN_X_ISSUER"),
            jwksUri = System.getenv("TOKEN_X_JWKS_URI"),
        )
    }
}

fun Application.ktorConfig(
    altinn3Config: Altinn3Config,
    altinn2Config: Altinn2Config,
    authConfig: AuthConfig,
    maskinportenConfig: MaskinportenConfig,
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
            log.info("Unexpected exception at ktor-toplevel: {}", cause.javaClass.canonicalName, cause)
            call.respond(HttpStatusCode.InternalServerError)
        }
    }

    authentication {
        jwt {
            val jwkProvider = JwkProviderBuilder(URI(authConfig.jwksUri).toURL())
                .cached(10, 24, TimeUnit.HOURS)
                .rateLimited(10, 1, TimeUnit.MINUTES)
                .build()

            verifier(jwkProvider, authConfig.issuer) {
                withIssuer(authConfig.issuer)
                withAudience(authConfig.clientId)

                withClaim("acr") { acr, _ ->
                    /* Trenger å støtte både Level4 og ideporten-loa-high, se:
                     * https://doc.nais.io/auth/tokenx/reference/#claim-mappings */
                    acr.asString() in listOf("idporten-loa-high", "Level4")
                }
                withClaimPresence("pid")
            }

            validate { credential ->
                InloggetBrukerPrincipal(
                    fnr = credential.getClaim("pid", String::class)!!,
                    clientId = credential.getClaim("client_id", String::class)!!,
                )
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
            call.principal<InloggetBrukerPrincipal>()?.clientId
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

    val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    install(MicrometerMetrics) {
        registry = appMicrometerRegistry
        // ...
    }

    install(ContentNegotiation) {
        json()
    }

    swaggerDocumentation()

    val redisClient = redisConfig.createClient()

    @OptIn(ExperimentalTime::class)
    val maskinportenA3 = Maskinporten(
        maskinportenConfig = maskinportenConfig,
        scope = "altinn:accessmanagement/authorizedparties.resourceowner",
        backgroundCoroutineScope = this,
    )

    @OptIn(ExperimentalTime::class)
    val maskinportenA2 = Maskinporten(
        maskinportenConfig = maskinportenConfig,
        scope = "altinn:serviceowner/reportees",
        backgroundCoroutineScope = this,
    )

    routing {
        route("internal") {
            get("prometheus") {
                call.respond<String>(appMicrometerRegistry.scrape())
            }
            get("isalive") {
                call.respondText("I'm alive")
            }
            get("isready") {
                call.respond(
                    if (maskinportenA3.isReady && maskinportenA2.isReady)
                        HttpStatusCode.OK
                    else
                        HttpStatusCode.ServiceUnavailable
                )
            }
        }

        get("/", {
            hidden = true
        }) {
            call.respondRedirect("/swagger-ui/index.html")
        }
        route("api.json") {
            openApiSpec()
        }
        route("swagger-ui") {
            swaggerUI("/api.json")
        }

        authenticate {
            val altinn3Client = Altinn3ClientImpl(
                altinn3Config = altinn3Config,
                maskinporten = maskinportenA3,
            )
            val altinn2Client = Altinn2ClientImpl(
                altinn2Config = altinn2Config,
                maskinporten = maskinportenA2,
            )

            val altinnTilgangerRedisClient = AltinnTilgangerRedisClientImpl(redisConfig)

            val altinnService = AltinnService(altinn2Client, altinn3Client, altinnTilgangerRedisClient)

            post("/altinn-tilganger", {
                description = "Hent tilganger fra Altinn for innlogget bruker."
                request {
                    // todo document optional callid header
                }
                response {
                    HttpStatusCode.OK to {
                        description = "Successful Request"
                        body<AltinnTilgangerResponse> {
                            exampleRef("Successful Respons", "tilganger_success")
                        }
                    }
                }
            }) {
                val fnr = call.principal<InloggetBrukerPrincipal>()!!.fnr
                val tilganger = altinnService.hentTilganger(fnr, this)

                call.respond(
                    AltinnTilgangerResponse.fromResult(
                        tilganger
                    )
                )
            }

            get("/whoami") {
                val clientId = call.principal<InloggetBrukerPrincipal>()!!.clientId
                call.respondText(Json.encodeToString(mapOf("clientId" to clientId)))
            }
        }
    }
}


@Serializable
class InloggetBrukerPrincipal(
    val fnr: String,
    val clientId: String,
) : Principal


@Description("Brukerens tilganger til Altinn 2 og Altinn 3 for en organisasjon")
@Serializable
data class AltinnTilgang(
    @Description("Organisasjonsnummer")
    @Example("11223344")
    val orgNr: String,
    @Description("Tilganger til Altinn 3")
    val altinn3Tilganger: Set<String>,
    @Description("Tilganger til Altinn 2")
    val altinn2Tilganger: Set<String>,
    @Description("list av underenheter til denne organisasjonen hvor brukeren har tilganger")
    val underenheter: List<AltinnTilgang>,
    @Description("Navn på organisasjonen")
    val name: String,
    @Description("Organisasjonsform. se https://www.brreg.no/bedrift/organisasjonsformer/")
    @Example("BEDR")
    val organizationForm: String,
)

@Serializable
data class AltinnTilgangerResponse(
    @Description("Om det var en feil ved henting av tilganger. Dersom denne er true kan det bety at ikke alle tilganger er hentet.")
    val isError: Boolean,
    @Description("Organisasjonshierarkiet med brukerens tilganger")
    val hierarki: List<AltinnTilgang>,
    @Description("Map fra organisasjonsnummer til tilganger. Convenience for å slå opp tilganger på orgnummer.")
    val orgNrTilTilganger: Map<String, Set<String>>,
    @Description("Map fra tilgang til organisasjonsnummer. Convenience for å slå opp orgnummer på tilgang.")
    val tilgangTilOrgNr: Map<String, Set<String>>,
) {
    companion object {
        fun fromResult(
            resultat: AltinnService.AltinnTilgangerResultat
        ): AltinnTilgangerResponse {
            val orgNrTilTilganger: Map<String, Set<String>> =
                resultat.altinnTilganger.flatMap { it.underenheter }
                    .associate {
                        it.orgNr to it.altinn2Tilganger + it.altinn3Tilganger
                    }

            val tilgangToOrgNr = orgNrTilTilganger.flatMap { (orgNr, tjenester) ->
                tjenester.map { it to orgNr }
            }.groupBy({ it.first }, { it.second }).mapValues {  it.value.toSet() }


            return AltinnTilgangerResponse(
                isError = resultat.isError,
                hierarki = resultat.altinnTilganger,
                orgNrTilTilganger = orgNrTilTilganger,
                tilgangTilOrgNr = tilgangToOrgNr,
            )
        }
    }
}
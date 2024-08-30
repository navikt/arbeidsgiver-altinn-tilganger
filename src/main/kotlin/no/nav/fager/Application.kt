package no.nav.fager

import com.auth0.jwk.JwkProviderBuilder
import io.github.smiley4.ktorswaggerui.SwaggerUI
import io.github.smiley4.ktorswaggerui.data.AuthScheme
import io.github.smiley4.ktorswaggerui.data.AuthType
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.routing.openApiSpec
import io.github.smiley4.ktorswaggerui.routing.swaggerUI
import io.github.smiley4.schemakenerator.reflection.processReflection
import io.github.smiley4.schemakenerator.swagger.compileReferencingRoot
import io.github.smiley4.schemakenerator.swagger.generateSwaggerSchema
import io.github.smiley4.schemakenerator.swagger.handleSchemaAnnotations
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Principal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.swagger.v3.oas.annotations.media.Schema
import kotlinx.serialization.Serializable
import org.slf4j.event.Level
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.time.ExperimentalTime

fun main() {
    embeddedServer(CIO, port = 8080, host = "0.0.0.0", module = {
        ktorConfig(
            altinn3Config = Altinn3Config.nais(),
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

@OptIn(ExperimentalLettuceCoroutinesApi::class)
fun Application.ktorConfig(
    altinn3Config: Altinn3Config,
    authConfig: AuthConfig,
    maskinportenConfig: MaskinportenConfig,
    redisConfig: RedisConfig,
) {
    val log = logger()

    install(Compression) {
        gzip {
            priority = 1.0
        }
        deflate {
            priority = 10.0
            minimumSize(1024) // condition
        }
    }
    install(DefaultHeaders) {
        // header("commit", "1234")
        // header("image", "")
        header("X-Engine", "Ktor") // will send this header with each response
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
                InloggetBrukerPrincipal(fnr = credential.getClaim("pid", String::class)!!)
            }
        }
    }
    install(CallLogging) {
        level = Level.INFO
        filter { call -> !call.request.path().startsWith("/internal/") }
        callIdMdc("call-id")

        if (System.getenv("NAIS_CLUSTER_NAME") != null) {
            disableDefaultColors()
        }
    }
    install(CallId) {
        header(HttpHeaders.XRequestId)
        verify { callId: String ->
            callId.isNotEmpty()
        }
    }

    val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    install(MicrometerMetrics) {
        registry = appMicrometerRegistry
        // ...
    }

    install(ContentNegotiation) {
        json()
    }

    install(SwaggerUI) {
        info {
            title = "Altinn tilgangsstyring av arbeidsgivere"
            version = "latest"
            description = "Example API for testing and demonstration purposes."
        }
        pathFilter = { _, url -> url.getOrNull(0) != "internal" }
        server {
            url = "http://0.0.0.0:8080"
            description = "Local mock server"
        }
        server {
            url = "https://arbeidsgiver-altinn-tilganger.intern.dev.nav.no"
            description = "dev-gcp"
        }
        security {
            defaultSecuritySchemeNames("BearerAuth")
            securityScheme("BearerAuth") {
                type = AuthType.HTTP
                scheme = AuthScheme.BEARER
                bearerFormat = "JWT"
            }
        }
        schemas {
            generator = { type ->
                type
                    .processReflection { }
                    .generateSwaggerSchema { }
                    .handleSchemaAnnotations()
                    .compileReferencingRoot()
            }
        }
        examples {
            example("Stor virksomhet") {
                value = AltinnOrganisasjon(
                    organisasjonsnummer = "1111111",
                    navn = "Foobar inc",
                    antallAnsatt = 3100,
                )
            }
            example("Liten cafe") {
                value = AltinnOrganisasjon(
                    organisasjonsnummer = "22222",
                    navn = "På hjørne",
                    antallAnsatt = 2,
                )
            }
        }
    }

    val redisClient = redisConfig.createClient()

    @OptIn(ExperimentalTime::class)
    val maskinporten = Maskinporten(
        maskinportenConfig = maskinportenConfig,
        scope = "altinn:accessmanagement/authorizedparties.resourceowner",
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
                call.respond(if (maskinporten.isReady) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable)
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

        post("/SetCache") {
            val keyValue = call.receive<SetBody>()
            val response = redisClient.connect { api ->
                api.set(keyValue.key, keyValue.value)
            }
            call.respond(GetValue(response))
        }

        post("/GetCache") {
            val key = call.receive<GetKey>().key
            val response = redisClient.connect { api ->
                api.get(key)
            }
            call.respond(GetValue(response))
        }

        authenticate {
            val altinn3Client = Altinn3Client(
                altinn3Config = altinn3Config,
                maskinporten = maskinporten,
            )

            post("/altinn-tilganger", {
                // TODO: document Bearer Auth
            }) {
                val fnr = call.principal<InloggetBrukerPrincipal>()!!.fnr
                val authorizedParties = altinn3Client.hentAuthorizedParties(fnr)
                call.respond(authorizedParties)
            }

            post("/json/kotlinx-serialization", {
                summary = "en kort beskrivesle"
                description = """
                    en lang beskrivelse.
                    This is our new endpoint. We can use markdown here: 
                    This text is *italics*. This is **bold**.
                    
                    Man kan ha avsnitt.
                    # overskrift?
                    ## underoverskrift?
                    Og [lenker](https://nrk.no).
                    
                    Og 
                    ```
                    fun foo(): Int {
                        return 0
                    }
                    ```
                """.trimIndent()

                request {
                    headerParameter<String>("authorization")
                    pathParameter<Int>("count")
                    body<AltinnOrganisasjon> {
                        description = "En organisasjon som input"
                    }
                }

                response {
                    HttpStatusCode.OK to {
                        description = "Successful Request"
                        body<AltinnOrganisasjon> {
                            description = "the response"
                            mediaTypes(ContentType.Application.Json)
                            example("possible return value") {
                                value = AltinnOrganisasjon(
                                    organisasjonsnummer = "11223344",
                                    antallAnsatt = 32,
                                    navn = "Ivarsen AS",
                                )
                            }
                            example("another possible return value") {
                                value = AltinnOrganisasjon(
                                    organisasjonsnummer = "99999999",
                                    antallAnsatt = 11,
                                    navn = "Gunnar AS",
                                )
                            }
                            exampleRef("Liten cafe")
                            exampleRef("Stor virksomhet")
                        }
                    }
                    HttpStatusCode.Unauthorized to {
                        description = "Bruker er ikke autentisert. Husket du authorization-header med bearer-token?"
                    }
                }
            }) {
                val body = call.receive<AltinnOrganisasjon>()
                println(body)
                call.respond<AltinnOrganisasjon>(body)
            }
        }
    }
}

@Serializable
@Schema(
    title = "Altinn organisasjons title",
    description = """Description for klassen.
Her kan vi ha mye tekst.

Vi kan ha et avsnitt om vi vil.
""",
)
data class AltinnOrganisasjon(
    @field:Schema(
        title = "Organisasjonsnummeret",
        description = "9-sifret greie",
        minimum = "0",
        maximum = "0",
        examples = ["111111111", "2222222", "33333333"]
    )
    val organisasjonsnummer: String,

    val navn: String,

    @field:Schema(
        title = "antall ansatte",
        description = "Antall ansatte i virksomheten",
        minimum = "0",
        maximum = "3333",
        example = "3",
    )
    val antallAnsatt: Int,

    @field:Schema(
        name = "name anstall ansatte",
        title = "antall ansatte",
        description = "Antall ansatte i virksomheten",
        minimum = "0",
        maximum = "3333",
        example = "3",
        minLength = 0,
        maxLength = 0,
        requiredMode = Schema.RequiredMode.AUTO,
        defaultValue = "0",
    )
    val someOtherNumber: Int? = 0,

    val innloggetBrukerPrincipal: InloggetBrukerPrincipal? = null,
)

@Serializable
class InloggetBrukerPrincipal(
    val fnr: String,
) : Principal

@Serializable
data class SetBody(
    val key: String,
    val value: String,
)

@Serializable
data class GetKey(
    val key: String,
)

@Serializable
data class GetValue(
    val value: String?,
)
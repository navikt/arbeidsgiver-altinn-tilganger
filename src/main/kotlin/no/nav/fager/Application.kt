package no.nav.fager

import com.auth0.jwk.JwkProviderBuilder
import io.github.smiley4.ktorswaggerui.SwaggerUI
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.routing.openApiSpec
import io.github.smiley4.ktorswaggerui.routing.swaggerUI
import io.github.smiley4.schemakenerator.reflection.processReflection
import io.github.smiley4.schemakenerator.swagger.compileReferencingRoot
import io.github.smiley4.schemakenerator.swagger.generateSwaggerSchema
import io.github.smiley4.schemakenerator.swagger.handleSchemaAnnotations
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.StaticCredentialsProvider
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.swagger.v3.oas.annotations.media.Schema
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.time.ExperimentalTime
import kotlinx.serialization.Serializable
import no.nav.fager.maskinporten.MaskinportenConfig
import no.nav.fager.maskinporten.MaskinportenPlugin
import org.slf4j.event.Level

fun main() {
    embeddedServer(CIO, port = 8080, host = "0.0.0.0", module = {
        ktorConfig(
            authConfig = AuthConfig.nais(),
            maskinportenConfig = MaskinportenConfig.nais()
        )
    })
        .start(wait = true)
}

class AuthConfig(
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

@OptIn(ExperimentalLettuceCoroutinesApi::class, ExperimentalTime::class)
fun Application.ktorConfig(authConfig: AuthConfig, maskinportenConfig: MaskinportenConfig) {
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

    authentication {
        jwt {
            val jwkProvider = JwkProviderBuilder(URI(authConfig.jwksUri).toURL())
                .cached(10, 24, TimeUnit.HOURS)
                .rateLimited(10, 1, TimeUnit.MINUTES)
                .build()

            verifier(jwkProvider, authConfig.issuer) {
                withIssuer(authConfig.issuer)
                withAudience(authConfig.clientId)
                withClaim("acr", "idporten-loa-high")
                withClaimPresence("pid")
            }

            validate { credential ->
                InloggetBrukerPrincipal(fnr = credential.getClaim("pid", String::class)!!)
            }
        }
    }
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/") }
        callIdMdc("call-id")
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
            url = "http://localhost:8080"
            description = "Local mock server"
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
    val uri = "redis://localhost:6379" //system.getEnv()
    val username = ""
    val password = "123"


    val redisURI = RedisURI.create(uri).apply {
        credentialsProvider = StaticCredentialsProvider(username, password.toCharArray())
    }

    val redisClient = RedisClient.create(redisURI)

    val maskinportenHttpClient = HttpClient(io.ktor.client.engine.cio.CIO) {
        install(MaskinportenPlugin) {
            this.maskinportenConfig = maskinportenConfig
            this.scope = "altinn:serviceowner/reportees"
        }
    }

    routing {
        route("internal") {
            get("prometheus") {
                call.respond<String>(appMicrometerRegistry.scrape())
            }
            get("isalive") {
                call.respondText("I'm alive")
            }
            get("isready") {
                call.respondText("I'm ready")
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
            val response = redisClient.redisClientConnection { api ->
                api.set(keyValue.key, keyValue.value)
            }

            call.respond(GetValue(response))
        }

        post("/GetCache") {
            val key = call.receive<GetKey>().key
            val response = redisClient.redisClientConnection{ api ->
                api.get(key)
            }
            call.respond(GetValue(response))
        }

        authenticate {
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
        get("/maskinporten-test") {
            val body = maskinportenHttpClient.get("http://arbeidsgiver-altinn-tilganger/some/api-endpoint/from/altinn").bodyAsText()
            call.respond(body)
        }
        get("/maskinporten-fake-endpoint") {
            val authorization = call.request.headers["authorization"]
            call.respondText(authorization ?: "no header found")
        }
    }
}

@OptIn(ExperimentalLettuceCoroutinesApi::class)
suspend fun <T> RedisClient.redisClientConnection(
    body: suspend (RedisCoroutinesCommands<String, String>) -> T
): T {
    return this.connect().use { connection ->
        val api = connection.coroutines()
        body(api)
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
    val fnr: String
) : Principal

@Serializable
data class SetBody(
    val key: String,
    val value: String
)

@Serializable
data class GetKey(
    val key: String
)

@Serializable
data class GetValue(
    val value: String?
)
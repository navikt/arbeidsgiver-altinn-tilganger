package no.nav.fager

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.github.smiley4.ktorswaggerui.SwaggerUI
import io.ktor.http.*
import io.ktor.resources.*
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
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.Resources
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import kotlinx.serialization.Serializable
import no.nav.fager.plugins.*
import org.slf4j.event.Level

fun main() {
    embeddedServer(CIO, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
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
    // Please read the jwt property from the config file if you are using EngineMain
    val jwtAudience = "jwt-audience"
    val jwtDomain = "https://jwt-provider-domain/"
    val jwtRealm = "ktor sample app"
    val jwtSecret = "secret"
    authentication {
        jwt {
            realm = jwtRealm
            verifier(
                JWT
                    .require(Algorithm.HMAC256(jwtSecret))
                    .withAudience(jwtAudience)
                    .withIssuer(jwtDomain)
                    .build()
            )
            validate { credential ->
                if (credential.payload.audience.contains(jwtAudience)) JWTPrincipal(credential.payload) else null
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
    install(Resources)

    val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    install(MicrometerMetrics) {
        registry = appMicrometerRegistry
        // ...
    }

    install(ContentNegotiation) {
        json()
    }

    install(SwaggerUI) {
        swagger {
            swaggerUrl = "swagger-ui"
            forwardRoot = true
        }
        info {
            title = "Example API"
            version = "latest"
            description = "Example API for testing and demonstration purposes."
        }
        server {
            url = "http://localhost:8080"
            description = "Development Server"
        }
    }
    routing {
        get("/internal/prometheus") {
            call.respond<String>(appMicrometerRegistry.scrape())
        }
        post("/json/kotlinx-serialization") {
            val body = call.receive<AltinnOrganisasjon>()
            println(body)
            call.respond<AltinnOrganisasjon>(body)
        }
        get("/internal/isalive") {
            call.respondText("I'm alive")
        }
        get("/internal/isready") {
            call.respondText("I'm ready")
        }

        /* eksempel på typesafe route */
        get<Articles> { article ->
            // Get all articles ...
            call.respond<String>("List of articles sorted starting from ${article.sort}")
        }
    }

}

data class AltinnOrganisasjon(
    val organisasjonsnummer: String,
    val navn: String,
    val antallAnsatt: Int
)

@Serializable
@Resource("/articles")
class Articles(val sort: String? = "new")

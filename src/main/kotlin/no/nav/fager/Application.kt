package no.nav.fager

import com.auth0.jwk.JwkProviderBuilder
import io.github.smiley4.ktorswaggerui.SwaggerUI
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
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import java.net.URI
import kotlinx.serialization.Serializable
import org.slf4j.event.Level
import java.util.concurrent.TimeUnit

fun main() {
    val authConfig = AuthConfig(
        clientId = System.getenv("TOKEN_X_CLIENT_ID"),
        issuer = System.getenv("TOKEN_X_ISSUER"),
        jwksUri = System.getenv("TOKEN_X_JWKS_URI"),
    )

    embeddedServer(CIO, port = 8080, host = "0.0.0.0", module = {
        ktorConfig(authConfig)
    })
        .start(wait = true)
}

class AuthConfig(
    val clientId: String,
    val issuer: String,
    val jwksUri: String,
)

fun Application.ktorConfig(authConfig: AuthConfig) {
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
        jwt() {
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

        authenticate {
            post("/json/kotlinx-serialization") {
                val body = call.receive<AltinnOrganisasjon>()
                println(body)
                call.respond<AltinnOrganisasjon>(body)
            }
        }
    }
}

@Serializable
data class AltinnOrganisasjon(
    val organisasjonsnummer: String,
    val navn: String,
    val antallAnsatt: Int
)

class InloggetBrukerPrincipal(
    val fnr: String
) : Principal
package no.nav.fager.fakes

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.pipeline.PipelineContext
import kotlinx.coroutines.runBlocking
import no.nav.fager.altinn.Altinn3Config
import org.slf4j.event.Level
import kotlin.test.fail

class FakeAltinn3Api {
    @Volatile
    var handler: (suspend PipelineContext<Unit, ApplicationCall>.(Any) -> Unit)? = null

    @Volatile
    var expectedPath: String? = null

    @Volatile
    var expectedMethod: HttpMethod? = null

    @Volatile
    var error: Throwable? = null

    fun start() {
        server.startAndWaitUntilReady()
    }

    fun stop() {
        server.stop()
    }

    private val server = embeddedServer(CIO, port = 0) {
        install(CallLogging) {
            level = Level.INFO
            filter { call -> !call.request.path().startsWith("/internal/") }
        }

        routing {
            get("/internal/isready") {
                call.respond(HttpStatusCode.OK)
            }

            post("{...}") {
                error = null

                if (call.request.path() != expectedPath) {
                    return@post call.respond(HttpStatusCode.NotFound)
                }
                if (call.request.httpMethod != expectedMethod) {
                    return@post call.respond(HttpStatusCode.NotFound)
                }

                try {
                    handler!!(it)
                } catch (e: Throwable) {
                    error = e
                    throw e
                }
            }

            get("{...}") {
                error = null

                if (call.request.path() != expectedPath) {
                    return@get call.respond(HttpStatusCode.NotFound)
                }
                if (call.request.httpMethod != expectedMethod) {
                    return@get call.respond(HttpStatusCode.NotFound)
                }

                try {
                    handler!!(it)
                } catch (e: Throwable) {
                    error = e
                    throw e
                }
            }
        }
    }

    fun assertNoAltinnErrors() {
        error?.let {
            fail("error occured in FakeAltinnApi handler.", it)
        }
        error = null
    }

    fun config(): Altinn3Config {
        val port = runBlocking {
            server.resolvedConnectors().first().port
        }
        return Altinn3Config(
            baseUrl = "http://localhost:$port",
            ocpApimSubscriptionKey = "someSubscriptionKey",
        )
    }
}


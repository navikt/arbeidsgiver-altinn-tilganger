package no.nav.fager.fakes

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.pipeline.PipelineContext
import kotlinx.coroutines.runBlocking
import no.nav.fager.altinn.Altinn2Config
import org.slf4j.event.Level
import kotlin.test.fail

class FakeApi {

    val stubs = mutableMapOf<Pair<HttpMethod, String>, (suspend PipelineContext<Unit, ApplicationCall>.(Any) -> Unit)>()

    val errors = mutableListOf<Throwable>()

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
                stubs[HttpMethod.Post to call.request.path()]?.let { handler ->
                    try {
                        handler(it)
                    } catch (e: Exception) {
                        errors.add(e)
                        throw e
                    }
                } ?: return@post call.respond(HttpStatusCode.NotFound)
            }

            get("{...}") {
                stubs[HttpMethod.Get to call.request.path()]?.let { handler ->
                    try {
                        handler(it)
                    } catch (e: Exception) {
                        errors.add(e)
                        throw e
                    }
                } ?: return@get call.respond(HttpStatusCode.NotFound)
            }
        }
    }

    fun assertNoErrors() {
        if (errors.isNotEmpty()) {
            fail("error occured in FakeApi handler. ${errors.map { it.message }}")
        }
        errors.clear()
    }

    val port
        get() = runBlocking {
            server.resolvedConnectors().first().port
        }

}

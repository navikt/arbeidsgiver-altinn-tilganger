package no.nav.fager.fakes

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import no.nav.fager.texas.TexasAuthConfig
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.slf4j.event.Level
import kotlin.test.fail

fun TexasAuthConfig.Companion.fake(fake: FakeApi) = TexasAuthConfig(
    tokenEndpoint = "http://localhost:${fake.port}/token",
    tokenExchangeEndpoint = "http://localhost:${fake.port}/exchange",
    tokenIntrospectionEndpoint = "http://localhost:${fake.port}/introspect",
)

class FakeApi : BeforeAllCallback, AfterAllCallback {

    val stubs = mutableMapOf<Pair<HttpMethod, String>, (suspend RoutingContext.(Any) -> Unit)>()

    val errors = mutableListOf<Throwable>()

    suspend fun start() {
        server.engine.startAndWaitUntilReady()
    }

    fun stop() {
        server.engine.stop()
    }

    private val server = embeddedServer(CIO, port = 0) {
        install(CallLogging) {
            level = Level.INFO
            //filter { call -> !call.request.path().startsWith("/internal/") }
        }

        install(ContentNegotiation) {
            json()
        }

        routing {
            get("/internal/isready") {
                call.response.status(HttpStatusCode.OK)
            }

            post("{...}") {
                stubs[HttpMethod.Post to call.request.path()]?.let { handler ->
                    try {
                        handler(this)
                    } catch (e: Exception) {
                        errors.add(e)
                        throw e
                    }
                } ?: return@post call.respond(HttpStatusCode.NotFound)
            }

            get("{...}") {
                stubs[HttpMethod.Get to call.request.path()]?.let { handler ->
                    try {
                        handler(this)
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
            server.engine.resolvedConnectors().first().port
        }

    override fun beforeAll(ctx: ExtensionContext) = runBlocking {
        start()
    }

    override fun afterAll(ctx: ExtensionContext) {
        stop()
    }

}


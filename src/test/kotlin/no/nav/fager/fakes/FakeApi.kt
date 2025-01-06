package no.nav.fager.fakes

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.runBlocking
import no.nav.fager.altinn.Altinn2Config
import no.nav.fager.texas.TexasAuthConfig
import org.slf4j.event.Level
import kotlin.test.fail

fun TexasAuthConfig.Companion.fake(fake: FakeApi) = TexasAuthConfig(
    tokenEndpoint = "http://localhost:${fake.port}/token",
    tokenExchangeEndpoint = "http://localhost:${fake.port}/exchange",
    tokenIntrospectionEndpoint = "http://localhost:${fake.port}/introspect",
)

fun Altinn2Config.Companion.fake(fake: FakeApi) = Altinn2Config(
    baseUrl = "http://localhost:${fake.port}",
    apiKey = "someApiKey",
)

class FakeApi : org.junit.rules.ExternalResource() {

    val stubs = mutableMapOf<Pair<HttpMethod, String>, (suspend PipelineContext<Unit, ApplicationCall>.(Any) -> Unit)>()

    val errors = mutableListOf<Throwable>()

    public override fun before() {
        start()
    }

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

        install(ContentNegotiation) {
            json()
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


package no.nav.fager.fakes

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.path
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
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

class FakeApi : AutoCloseable {

    val stubs = mutableMapOf<Pair<HttpMethod, String>, (suspend RoutingContext.(Any) -> Unit)>()

    val errors = mutableListOf<Throwable>()

    suspend fun start() {
        server.start(false)
        server.engine.waitUntilReady()
    }

    fun stop() {
        server.stop()
    }

    private val server = embeddedServer(CIO, port = 0) {
        install(CallLogging) {
            level = Level.INFO
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
                } ?: return@post call.response.status(HttpStatusCode.NotFound)
            }

            get("{...}") {
                stubs[HttpMethod.Get to call.request.path()]?.let { handler ->
                    try {
                        handler(this)
                    } catch (e: Exception) {
                        errors.add(e)
                        throw e
                    }
                } ?: return@get call.response.status(HttpStatusCode.NotFound)
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
            server.application.engine.resolvedConnectors().first().port
        }

    override fun close() {
        stop()
    }

}

fun testWithFakeApi(
    block: suspend ApplicationTestBuilder.(fakeApi: FakeApi) -> Unit
) = testApplication {
    FakeApi().use { fakeApi ->
        runBlocking {
            fakeApi.start()
        }
        block(fakeApi)
    }
}


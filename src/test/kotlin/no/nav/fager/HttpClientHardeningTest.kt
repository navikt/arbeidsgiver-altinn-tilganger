package no.nav.fager

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.get
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.network.sockets.SocketTimeoutException
import io.ktor.server.response.respondText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import no.nav.fager.fakes.FakeApi
import no.nav.fager.infrastruktur.defaultHttpClient
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class HttpClientHardeningTest {

    private fun withFakeApi(block: (FakeApi) -> Unit) {
        FakeApi().use { fakeApi ->
            runBlocking { fakeApi.start() }
            block(fakeApi)
        }
    }

    @Test
    fun `customizeRetry med retryOnServerErrors 0 gir nøyaktig ett kall ved 500 fra server`() = withFakeApi { fakeApi ->
        val calls = AtomicInteger(0)
        fakeApi.stubs[HttpMethod.Get to "/boom"] = {
            calls.incrementAndGet()
            call.respondText("boom", status = HttpStatusCode.InternalServerError)
        }

        val client = defaultHttpClient(customizeRetry = { retryOnServerErrors(0) })

        assertFailsWith<ServerResponseException> {
            runBlocking { client.get("http://localhost:${fakeApi.port}/boom") }
        }

        assertEquals(1, calls.get(), "server-error skal ikke retryes når customizeRetry setter retryOnServerErrors(0)")
    }

    @Test
    fun `default retryOnServerErrors gir fire kall ved 500 fra server`() = withFakeApi { fakeApi ->
        val calls = AtomicInteger(0)
        fakeApi.stubs[HttpMethod.Get to "/boom"] = {
            calls.incrementAndGet()
            call.respondText("boom", status = HttpStatusCode.InternalServerError)
        }

        val client = defaultHttpClient()

        assertFailsWith<ServerResponseException> {
            runBlocking { client.get("http://localhost:${fakeApi.port}/boom") }
        }

        assertEquals(4, calls.get(), "default skal gi 1 forsøk + 3 retries")
    }

    @Test
    fun `retryOnExceptionIf retryer fortsatt ved SocketTimeoutException`() = withFakeApi { fakeApi ->
        val calls = AtomicInteger(0)
        val timeoutMs = 10L
        fakeApi.stubs[HttpMethod.Get to "/treg"] = {
            calls.incrementAndGet()
            delay((timeoutMs + 10).milliseconds)
            call.respondText("omsider", status = HttpStatusCode.OK)
        }

        val client = defaultHttpClient(configure = {
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                socketTimeoutMillis = timeoutMs
            }
        })

        assertFailsWith<SocketTimeoutException> {
            runBlocking { client.get("http://localhost:${fakeApi.port}/treg") }
        }

        assertEquals(4, calls.get(), "socket timeout skal gi 1 forsøk + 3 retries")
    }
}

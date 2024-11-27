package no.nav.fager

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.get
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import no.nav.fager.fakes.FakeApi
import no.nav.fager.fakes.fake
import no.nav.fager.maskinporten.Maskinporten
import no.nav.fager.maskinporten.MaskinportenPlugin
import no.nav.fager.texas.TexasAuthConfig
import no.nav.fager.texas.TokenResponse
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class MaskinportenTest {
    companion object {
        private val callCount = AtomicInteger(0)
        private val token = AtomicReference("")

        @org.junit.ClassRule
        @JvmField
        val fakeTexas = FakeApi()
    }

    @Test
    fun `plugin legger på token`() = runTest {
        fakeTexas.stubs[HttpMethod.Post to "/token"] = {
            callCount.incrementAndGet()
            call.respondText(
                //language=json
                """
                {
                  "access_token": "${token.get()}",
                  "expires_in": 3600
                }
                """.trimIndent(),
                ContentType.Application.Json
            )
        }

        val maskinporten = Maskinporten(
            texasAuthConfig = TexasAuthConfig.fake(fakeTexas),
            scope = "1234",
            backgroundCoroutineScope = null,
            timeSource = testTimeSource,
        )


        val fakeProtectedAPiEndpoint = MockEngine(MockEngineConfig().apply {
            addHandler {
                respond(
                    content = """{"bla":"bla", "bla": 43}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        })

        val httpClient = HttpClient(fakeProtectedAPiEndpoint) {
            install(MaskinportenPlugin) {
                this.maskinporten = maskinporten
            }
        }

        token.set("first_token")
        maskinporten.refreshTokenIfNeeded()

        assertEquals(1, callCount.get(), "One call to maskinporten on initial refresh")

        httpClient.get("/some/protected/endpoint")

        assertEquals(1, callCount.get(), "Still only one call to maskinporten because of caching")
        assertNotNull(fakeProtectedAPiEndpoint.requestHistory.lastOrNull()).apply {
            assertEquals("Bearer first_token", headers["authorization"])
        }

        token.set("second_token")

        httpClient.get("/some/protected/endpoint")
        assertEquals(1, callCount.get(), "Still only one call to maskinporten because of caching")
        assertNotNull(fakeProtectedAPiEndpoint.requestHistory.lastOrNull()).apply {
            assertEquals("Bearer first_token", headers["authorization"])
        }

        advanceTimeBy(1.hours)
        maskinporten.refreshTokenIfNeeded()

        assertEquals(
            2,
            callCount.get(),
            "Expect two calls to maskinporten, as first token has expired."
        )

        httpClient.get("/some/protected/endpoint")
        assertNotNull(fakeProtectedAPiEndpoint.requestHistory.lastOrNull()).apply {
            assertEquals("Bearer second_token", headers["authorization"])
        }

        assertEquals(
            2,
            callCount.get(),
            "Still two calls to maskinporten, because of caching."
        )
    }

    @Test
    fun `printing av accesstoken lekker ikke secret`() {
        val secret = "12355jkasdklajsflajflj"
        val tokenEndpointResponse = TokenResponse.Success(accessToken = secret, expiresInSeconds = 33)
        assertFalse(tokenEndpointResponse.toString().contains(secret))
    }
}
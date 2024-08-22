package no.nav.fager

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import no.nav.fager.maskinporten.Maskinporten
import no.nav.fager.maskinporten.MaskinportenConfig
import no.nav.fager.maskinporten.MaskinportenPlugin
import no.nav.fager.maskinporten.TokenEndpointResponse

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class MaskinportenTest {
    @Test
    fun `printing av MaskinportenConfig lekker ikke secret`() {
        val someSecret = "1234123ahjasfadskfjasfjl"
        val maskinportenConfig = MaskinportenConfig(
            clientId = "someClientId",
            clientJwk = someSecret,
            issuer = "the issuer",
            tokenEndpoint = "an url",
        )

        assertFalse(maskinportenConfig.toString().contains(someSecret))
    }

    @Test
    fun `printing av accesstoken lekker ikke secret`() {
        val secret = "12355jkasdklajsflajflj"
        val tokenEndpointResponse = TokenEndpointResponse(accessToken = secret, expiresIn = 33)
        assertFalse(tokenEndpointResponse.toString().contains(secret))
    }

    @Test
    fun `plugin legger på token`() = runTest {
        val fakeTokenEndpoint = MockEngine(
            MockEngineConfig().apply {
                fun addHandler(token: String, expiresIn: Duration) {
                    addHandler {
                        respond(
                            content = """{"access_token":"$token", "expires_in": ${expiresIn.seconds}}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }

                reuseHandlers = false
                addHandler("first_token", Duration.ofMinutes(10))
                addHandler("second_token", Duration.ofMinutes(10))
            }
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

        val maskinporten = Maskinporten(
            maskinportenConfig = maskinportenMockConfig,
            httpClientEngine = fakeTokenEndpoint,
            coroutineScope = backgroundScope,
            timeSource = testScheduler.timeSource,
            scope = "1234",
        )

        val httpClient = HttpClient(fakeProtectedAPiEndpoint) {
            install(MaskinportenPlugin) {
                this.maskinporten = maskinporten
            }
        }

        assertFails("Using the client without starting maskinporten-service fails") {
            httpClient.get("/some/protected/endpoint")
        }

        assertEquals(0, fakeTokenEndpoint.requestHistory.size, "No calls should be made by starting maskinporten")

        maskinporten.refreshTokenIfNeeded()

        assertEquals(1, fakeTokenEndpoint.requestHistory.size, "One call to maskinporten on initial refresh")

        httpClient.get("/some/protected/endpoint")

        assertEquals(1, fakeTokenEndpoint.requestHistory.size, "Still only one call to maskinporten because of caching")
        assertNotNull(fakeProtectedAPiEndpoint.requestHistory.lastOrNull()).apply {
            assertEquals("Bearer first_token", headers["authorization"])
        }

        httpClient.get("/some/protected/endpoint")
        assertEquals(1, fakeTokenEndpoint.requestHistory.size, "Still only one call to maskinporten because of caching")
        assertNotNull(fakeProtectedAPiEndpoint.requestHistory.lastOrNull()).apply {
            assertEquals("Bearer first_token", headers["authorization"])
        }

        advanceTimeBy(20.minutes)
        maskinporten.refreshTokenIfNeeded()

        assertEquals(2, fakeTokenEndpoint.requestHistory.size, "Expect two calls to maskinporten, as first token has expired.")

        httpClient.get("/some/protected/endpoint")
        assertNotNull(fakeProtectedAPiEndpoint.requestHistory.lastOrNull()).apply {
            assertEquals("Bearer second_token", headers["authorization"])
        }

        assertEquals(2, fakeTokenEndpoint.requestHistory.size, "Expect two calls to maskinporten, as first token has expired.")
    }
}
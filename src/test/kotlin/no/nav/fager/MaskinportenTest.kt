package no.nav.fager

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import no.nav.fager.fakes.FakeMaskinporten
import no.nav.fager.maskinporten.Maskinporten
import no.nav.fager.maskinporten.MaskinportenConfig
import no.nav.fager.maskinporten.MaskinportenPlugin
import no.nav.fager.maskinporten.TokenEndpointResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class MaskinportenTest {
    companion object {
        @org.junit.ClassRule
        @JvmField
        val fakeMaskinporten = FakeMaskinporten()
    }

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
        val maskinporten = Maskinporten(
            maskinportenConfig = fakeMaskinporten.config(),
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

        fakeMaskinporten.accessToken = "first_token"
        maskinporten.refreshTokenIfNeeded()

        assertEquals(1, fakeMaskinporten.callCount.get(), "One call to maskinporten on initial refresh")

        httpClient.get("/some/protected/endpoint")

        assertEquals(1, fakeMaskinporten.callCount.get(), "Still only one call to maskinporten because of caching")
        assertNotNull(fakeProtectedAPiEndpoint.requestHistory.lastOrNull()).apply {
            assertEquals("Bearer first_token", headers["authorization"])
        }

        httpClient.get("/some/protected/endpoint")
        assertEquals(1, fakeMaskinporten.callCount.get(), "Still only one call to maskinporten because of caching")
        assertNotNull(fakeProtectedAPiEndpoint.requestHistory.lastOrNull()).apply {
            assertEquals("Bearer first_token", headers["authorization"])
        }

        fakeMaskinporten.accessToken = "second_token"

        advanceTimeBy(1.hours)
        maskinporten.refreshTokenIfNeeded()

        assertEquals(
            2,
            fakeMaskinporten.callCount.get(),
            "Expect two calls to maskinporten, as first token has expired."
        )

        httpClient.get("/some/protected/endpoint")
        assertNotNull(fakeProtectedAPiEndpoint.requestHistory.lastOrNull()).apply {
            assertEquals("Bearer second_token", headers["authorization"])
        }

        assertEquals(
            2,
            fakeMaskinporten.callCount.get(),
            "Still two calls to maskinporten, because of caching."
        )
    }
}
package no.nav.fager

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.response.respondText
import no.nav.fager.fakes.fake
import no.nav.fager.fakes.testWithFakeApi
import no.nav.fager.texas.AuthClient
import no.nav.fager.texas.IdentityProvider
import no.nav.fager.texas.TexasAuthClientPlugin
import no.nav.fager.texas.TexasAuthConfig
import no.nav.fager.texas.TokenResponse
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds

class TexasTest {

    @Test
    fun `plugin legger på token`() = testWithFakeApi { fakeTexas ->
        val token = AtomicReference("")
        fakeTexas.stubs[HttpMethod.Post to "/token"] = {
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

        val texasAuthConfig = TexasAuthConfig.fake(fakeTexas)

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
            install(TexasAuthClientPlugin) {
                authClient = AuthClient(texasAuthConfig, IdentityProvider.MASKINPORTEN)
                fetchToken = { it.token("1234") }
            }
        }

        token.set("first_token")
        httpClient.get("/some/protected/endpoint")
        assertNotNull(fakeProtectedAPiEndpoint.requestHistory.lastOrNull()).apply {
            assertEquals("Bearer first_token", headers["authorization"])
        }

        token.set("second_token")
        httpClient.get("/some/protected/endpoint")
        assertNotNull(fakeProtectedAPiEndpoint.requestHistory.lastOrNull()).apply {
            assertEquals("Bearer second_token", headers["authorization"])
        }
    }

    @Test
    fun `AuthClient retryer ikke ved 500 fra texas`() = testWithFakeApi { fakeTexas ->
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        fakeTexas.stubs[HttpMethod.Post to "/token"] = {
            calls.incrementAndGet()
            call.respondText(
                //language=json
                """{"error": "server_error", "error_description": "maskinporten timeout"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }

        val texasAuthConfig = TexasAuthConfig.fake(fakeTexas)
        val authClient = AuthClient(texasAuthConfig, IdentityProvider.MASKINPORTEN)

        val response = authClient.token("1234")

        assertEquals(1, calls.get(), "texas 5xx skal ikke retryes av AuthClient")
        assertNotNull(response as? TokenResponse.Error).apply {
            assertEquals(HttpStatusCode.InternalServerError, status)
        }
    }

    @Test
    fun `AuthClient har request-timeout mot texas`() = testWithFakeApi { fakeTexas ->
        val timeoutMs = 10L
        fakeTexas.stubs[HttpMethod.Post to "/token"] = {
            kotlinx.coroutines.delay((timeoutMs + 10).milliseconds)
            call.respondText(
                //language=json
                """{"access_token": "sent", "expires_in": 3600}""",
                ContentType.Application.Json,
            )
        }

        val texasAuthConfig = TexasAuthConfig.fake(fakeTexas)
        val authClient = AuthClient(texasAuthConfig, IdentityProvider.MASKINPORTEN, timeoutMs)

        assertFailsWith<io.ktor.client.plugins.HttpRequestTimeoutException> {
            authClient.token("1234")
        }
    }

    @Test
    fun `printing av accesstoken lekker ikke secret`() {
        val secret = "12355jkasdklajsflajflj"
        val tokenEndpointResponse = TokenResponse.Success(accessToken = secret, expiresInSeconds = 33)
        assertFalse(tokenEndpointResponse.toString().contains(secret))
    }
}
package no.nav.fager

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.response.*
import kotlinx.coroutines.test.runTest
import no.nav.fager.fakes.FakeApi
import no.nav.fager.fakes.fake
import no.nav.fager.texas.*
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.set
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class TexasTest {
    companion object {
        private val token = AtomicReference("")

        @JvmField
        @RegisterExtension
        val fakeTexas = FakeApi()
    }

    @Test
    fun `plugin legger på token`() = runTest {
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
    fun `printing av accesstoken lekker ikke secret`() {
        val secret = "12355jkasdklajsflajflj"
        val tokenEndpointResponse = TokenResponse.Success(accessToken = secret, expiresInSeconds = 33)
        assertFalse(tokenEndpointResponse.toString().contains(secret))
    }
}
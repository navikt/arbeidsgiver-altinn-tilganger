package no.nav.fager

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AuthTest {
    private val authConfig = AuthConfig(
        clientId = "local:fager:arbeidsgiver-altinn-tilganger",
        issuer = "http://localhost:9000/tokenx",
        jwksUri = "http://localhost:9000/tokenx/jwks",
    )

    @Test
    fun `internal isalive is open`() = testApplication {
        application {
            ktorConfig(authConfig = authConfig)
        }
        client.get("/internal/isalive").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun `internal isready is open`() = testApplication {
        application {
            ktorConfig(authConfig = authConfig)
        }
        client.get("/internal/isready").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun `internal prometheus is open`() = testApplication {
        application {
            ktorConfig(authConfig = authConfig)
        }
        client.get("/internal/prometheus").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun `json serialize reject unauthenticated call`() = testApplication {
        application {
            ktorConfig(authConfig = authConfig)
        }
        client.post("/json/kotlinx-serialization") {
            setBody(""" """.trimIndent())
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `json serialize accepts authenticated call`() = testApplication {
        application {
            ktorConfig(authConfig = authConfig)
        }
        client.post("/json/kotlinx-serialization") {
            authorization(fnr = "11111111111")
            contentType(ContentType.Application.Json)
            setBody("""{
                    "organisasjonsnummer": "",
                    "navn": "",
                    "antallAnsatt": 1
                }""".trimIndent())
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    private suspend fun HttpRequestBuilder.authorization(fnr: String) {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                })
            }
        }
        val response = client.post("http://localhost:9000/tokenx/token") {
            header("content-type", "application/x-www-form-urlencoded")
            setBody("""grant_type=client_credentials&client_id=1234&client_secret=1234&scope=$fnr""".trimIndent())
        }
        val token = response.body<AccessToken>().accessToken
        header("Authorization", "Bearer $token")
    }

    @Serializable()
    class AccessToken(
        @SerialName("access_token") val accessToken: String,
    )
}


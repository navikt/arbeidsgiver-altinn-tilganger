package no.nav.fager

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthTest {
    @Test
    fun `internal isalive is open`() = testApplication {
        application {
            ktorConfig(authConfig = mockOauth2ServerConfig)
        }
        client.get("/internal/isalive").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun `internal isready is open`() = testApplication {
        application {
            ktorConfig(authConfig = mockOauth2ServerConfig)
        }
        client.get("/internal/isready").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun `internal prometheus is open`() = testApplication {
        application {
            ktorConfig(authConfig = mockOauth2ServerConfig)
        }
        client.get("/internal/prometheus").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun `json serialize reject unauthenticated call`() = testApplication {
        application {
            ktorConfig(authConfig = mockOauth2ServerConfig)
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
            ktorConfig(authConfig = mockOauth2ServerConfig)
        }
        client.post("/json/kotlinx-serialization") {
            authorization(subject = "acr-high-11111111111")
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

    /*
    * Vi ønsker egentlig 403 - Forbidden men vi vet ikke helt hvordan vi får det til i JWT
     */
    @Test
    fun `json serialize reject low authenticated call`() = testApplication {
        application {
            ktorConfig(authConfig = mockOauth2ServerConfig)
        }
        client.post("/json/kotlinx-serialization") {
            authorization(subject="acr-low-33333333333")
            contentType(ContentType.Application.Json)
            setBody("""{
                    "organisasjonsnummer": "",
                    "navn": "",
                    "antallAnsatt": 1
                }""".trimIndent())
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `json serialize reject other autience call`() = testApplication {
        application {
            ktorConfig(authConfig = mockOauth2ServerConfig)
        }
        client.post( "/json/kotlinx-serialization") {
            authorization("wrong-audience-44444444444")
            contentType(ContentType.Application.Json)
            setBody("""{
                    "organisasjonsnummer": "",
                    "navn": "",
                    "antallAnsatt": 1
                }""".trimIndent())
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }
}


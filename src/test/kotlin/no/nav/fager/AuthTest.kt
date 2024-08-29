package no.nav.fager

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import no.nav.fager.fakes.FakeApplication
import no.nav.fager.fakes.authorization
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthTest {
    companion object {
        @org.junit.ClassRule
        @JvmField
        val app = FakeApplication()
    }

    @Test
    fun `internal isalive is open`() = app.runTest {
        client.get("/internal/isalive").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun `internal isready is open`() = app.runTest {
        client.get("/internal/isready").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun `internal prometheus is open`() = app.runTest {
        client.get("/internal/prometheus").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun `json serialize reject unauthenticated call`() = app.runTest {
        client.post("/json/kotlinx-serialization") {
            setBody(""" """.trimIndent())
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `json serialize accepts authenticated call`() = app.runTest {
        client.post("/json/kotlinx-serialization") {
            authorization(subject = "acr-high-11111111111")
            contentType(ContentType.Application.Json)
            setBody(
                """{
                    "organisasjonsnummer": "",
                    "navn": "",
                    "antallAnsatt": 1
                }""".trimIndent()
            )
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    /*
    * Vi ønsker egentlig 403 - Forbidden men vi vet ikke helt hvordan vi får det til i JWT
     */
    @Test
    fun `json serialize reject low authenticated call`() = app.runTest {
        client.post("/json/kotlinx-serialization") {
            authorization(subject = "acr-low-33333333333")
            contentType(ContentType.Application.Json)
            setBody(
                """{
                    "organisasjonsnummer": "",
                    "navn": "",
                    "antallAnsatt": 1
                }""".trimIndent()
            )
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `json serialize reject other autience call`() = app.runTest {
        client.post("/json/kotlinx-serialization") {
            authorization("wrong-audience-44444444444")
            contentType(ContentType.Application.Json)
            setBody(
                """{
                    "organisasjonsnummer": "",
                    "navn": "",
                    "antallAnsatt": 1
                }""".trimIndent()
            )
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

}


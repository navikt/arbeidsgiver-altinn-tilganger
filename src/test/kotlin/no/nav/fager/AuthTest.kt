package no.nav.fager

import io.ktor.client.request.*
import io.ktor.http.HttpStatusCode
import no.nav.fager.fakes.FakeApplication
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthTest {
    companion object {
        @JvmField
        @RegisterExtension
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
    fun `rejects unauthenticated call`() = app.runTest {
        client.get("/whoami").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `accepts authenticated call`() = app.runTest {
        client.get("/whoami") {
            header("Authorization", "Bearer acr-high-11111111111")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    /*
    * Vi ønsker egentlig 403 - Forbidden men vi vet ikke helt hvordan vi får det til i JWT
     */
    @Test
    fun `rejects low authenticated call`() = app.runTest {
        client.get("/whoami") {
            header("Authorization", "Bearer acr-low-33333333333")
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `rejects other autience call`() = app.runTest {
        client.get("/whoami") {
            header("Authorization", "Bearer wrong-audience-44444444444")
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

}


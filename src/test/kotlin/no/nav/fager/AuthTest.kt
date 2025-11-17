package no.nav.fager

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import no.nav.fager.fakes.testWithFakeApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthTest {

    @Test
    fun `internal isalive is open`() = testWithFakeApplication {
        client.get("/internal/isalive").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun `internal isready is open`() = testWithFakeApplication {
        client.get("/internal/isready").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun `internal prometheus is open`() = testWithFakeApplication {
        client.get("/internal/prometheus").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun `rejects unauthenticated call`() = testWithFakeApplication {
        client.get("/whoami").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `accepts authenticated call`() = testWithFakeApplication {
        client.get("/whoami") {
            header("Authorization", "Bearer idporten-loa-high:11111111111")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    /*
    * Vi ønsker egentlig 403 - Forbidden men vi vet ikke helt hvordan vi får det til i JWT
     */
    @Test
    fun `rejects low authenticated call`() = testWithFakeApplication {
        client.get("/whoami") {
            header("Authorization", "Bearer idporten-loa-low:33333333333")
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `rejects other autience call`() = testWithFakeApplication {
        client.get("/whoami") {
            header("Authorization", "Bearer wrong-audience-44444444444")
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

}


package no.nav.fager

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import no.nav.fager.fakes.testWithSharedFakeApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthTest {

    @Test
    fun `internal isalive is open`() = testWithSharedFakeApplication {
        client.get("/internal/isalive").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun `internal isready is open`() = testWithSharedFakeApplication {
        client.get("/internal/isready").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun `internal prometheus is open`() = testWithSharedFakeApplication {
        client.get("/internal/prometheus").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun `rejects unauthenticated call`() = testWithSharedFakeApplication {
        client.get("/whoami").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `accepts authenticated call`() = testWithSharedFakeApplication {
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
    fun `rejects low authenticated call`() = testWithSharedFakeApplication {
        client.get("/whoami") {
            header("Authorization", "Bearer idporten-loa-low:33333333333")
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `rejects other autience call`() = testWithSharedFakeApplication {
        client.get("/whoami") {
            header("Authorization", "Bearer wrong-audience-44444444444")
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

}

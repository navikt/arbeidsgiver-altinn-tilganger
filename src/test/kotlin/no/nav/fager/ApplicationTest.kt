package no.nav.fager

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*
import no.nav.fager.plugins.*

class ApplicationTest {
    @Test
    fun testRoot() = testApplication {
        application {
            module()
        }
        client.get("/internal/isalive").apply {
            assertEquals(HttpStatusCode.OK, status)
            assertEquals("I'm alive", bodyAsText())
        }
    }
}

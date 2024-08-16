package no.nav.fager

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class ApplicationTest {
    @Test
    fun testRoot() = testApplication {
        application {
            ktorConfig()
        }
        client.get("/internal/isalive").apply {
            assertEquals(HttpStatusCode.BadGateway, status)
            assertEquals("I'm alive", bodyAsText())
        }
    }
}

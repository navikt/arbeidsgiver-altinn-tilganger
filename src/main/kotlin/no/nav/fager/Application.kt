package no.nav.fager

import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import no.nav.fager.plugins.*

fun main() {
    embeddedServer(CIO, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    configureHTTP()
    configureSecurity()
    configureMonitoring()
    configureSerialization()
    configureRouting()
}

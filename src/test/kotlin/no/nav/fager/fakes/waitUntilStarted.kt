package no.nav.fager.fakes

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import no.nav.fager.infrastruktur.logger


fun EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>.startAndWaitUntilReady() {
    start()
    waitUntilReady()
}

fun EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>.waitUntilReady() {
    val log = logger()
    val port = runBlocking {
        engine.resolvedConnectors().first().port
    }

    val client = HttpClient(io.ktor.client.engine.cio.CIO)
    suspend fun isAlive() = runCatching {
        client.get("http://localhost:$port/internal/isready").status == HttpStatusCode.OK
    }.getOrElse {
        log.warn("not alive yet: $it")
        false
    }

    runBlocking {
        while (!isAlive()) {
            delay(1)
        }
    }
}

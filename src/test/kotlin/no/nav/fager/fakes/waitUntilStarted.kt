package no.nav.fager.fakes

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.engine.*
import kotlinx.coroutines.delay
import no.nav.fager.infrastruktur.logger



suspend fun ApplicationEngine.waitUntilReady() {
    val log = logger()

    val client = HttpClient(CIO)
    suspend fun isAlive() = runCatching {
        val port = resolvedConnectors().first().port
        client.get("http://localhost:$port/internal/isready").status == HttpStatusCode.OK
    }.getOrElse {
        log.warn("not alive yet: $it", it)
        false
    }

    while (!isAlive()) {
        delay(100)
    }
}

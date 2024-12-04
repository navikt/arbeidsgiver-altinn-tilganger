package no.nav.fager.fakes

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.engine.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import no.nav.fager.infrastruktur.logger


suspend fun ApplicationEngine.startAndWaitUntilReady() {
    startSuspend()
    waitUntilReady()
}

fun ApplicationEngine.waitUntilReady() {
    val log = logger()
    val port = runBlocking {
        resolvedConnectors().first().port
    }

    val client = HttpClient(CIO)
    suspend fun isAlive() = runCatching {
        client.get("http://localhost:$port/internal/isready", {
            timeout {
                requestTimeoutMillis = 100
                connectTimeoutMillis = 100
                socketTimeoutMillis = 100
            }
        }).status == HttpStatusCode.OK
    }.getOrElse {
        log.warn("not alive yet: $it")
        false
    }

    runBlocking {
        while (!isAlive()) {
            delay(1000)
        }
    }
}

package no.nav.fager.infrastruktur

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.network.sockets.*
import io.ktor.serialization.kotlinx.json.*
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.Json
import java.io.EOFException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLHandshakeException

fun defaultHttpClient(
    configure: HttpClientConfig<CIOEngineConfig>.() -> Unit = {}
) = HttpClient(CIO) {
    expectSuccess = true
    install(HttpRequestRetry) {
        retryOnServerErrors(3)
        retryOnExceptionIf(3) { _, cause ->
            when (cause) {
                is SocketTimeoutException,
                is EOFException,
                is SSLHandshakeException,
                is ClosedReceiveChannelException,
                is HttpRequestTimeoutException -> true

                else -> false
            }
        }

        delayMillis { 250L }
    }

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }

    install(Logging) {
        sanitizeHeader {
            true
        }
    }

    configure()
}

val httpClientTaggedTimerTimer = ConcurrentHashMap<String, Timer>()

fun withTimer(name: String): Timer =
    httpClientTaggedTimerTimer.computeIfAbsent(name) {
        Timer.builder("http_client")
            .tag("name", it)
            .publishPercentileHistogram()
            .register(Metrics.meterRegistry)
    }
package no.nav.fager.infrastruktur

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.CIOEngineConfig
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.Logging
import io.ktor.network.sockets.SocketTimeoutException
import io.ktor.serialization.kotlinx.json.json
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.Json
import java.io.EOFException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLHandshakeException

fun defaultHttpClient(
    customizeMetrics: HttpClientMetricsFeature.Config.() -> Unit = {},
    configure: HttpClientConfig<CIOEngineConfig>.() -> Unit = {}
) = HttpClient(CIO) {
    expectSuccess = true
    install(HttpRequestRetry) {
        retryOnServerErrors(3)
        retryOnExceptionIf(3) { _, cause ->
            when (cause) {
                is SocketTimeoutException,
                is ConnectTimeoutException,
                is EOFException,
                is SSLHandshakeException,
                is ClosedReceiveChannelException,
                    -> true

                else -> false
            }
        }

        delayMillis { 250L }
    }

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }


    install(HttpClientMetricsFeature) {
        registry = Metrics.meterRegistry
        customizeMetrics()
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
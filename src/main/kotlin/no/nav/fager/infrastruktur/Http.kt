package no.nav.fager.infrastruktur

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.network.sockets.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.Json
import javax.net.ssl.SSLHandshakeException

fun defaultHttpClient(
    configure: HttpClientConfig<CIOEngineConfig>.() -> Unit = {}
) = HttpClient(CIO) {
    expectSuccess = true
    install(HttpRequestRetry) {
        maxRetries = 3
        retryOnExceptionIf { _, cause ->
            cause is SocketTimeoutException ||
                    cause is SSLHandshakeException ||
                    cause is ClosedReceiveChannelException
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
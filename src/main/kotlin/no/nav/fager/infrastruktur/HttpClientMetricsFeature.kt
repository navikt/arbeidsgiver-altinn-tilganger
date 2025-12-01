package no.nav.fager.infrastruktur

import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.encodedPath
import io.ktor.util.AttributeKey
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Timer


/**
 * inspired by [io.ktor.server.metrics.micrometer.MicrometerMetrics], but for clients.
 * this feature/plugin generates the following metrics:
 * (x = ktor.http.client, but can be overridden)
 *
 * x.requests: a timer for measuring the time of each request. This metric provides a set of tags for monitoring request data, including http method, path, status
 *
 */
class HttpClientMetricsFeature internal constructor(
    private val registry: MeterRegistry,
    private val clientName: String,
    private val staticPath: String?,
    private val canonicalizer: ((String) -> String)? = null,
) {
    /**
     * [HttpClientMetricsFeature] configuration that is used during installation
     */
    class Config {
        var clientName: String = "ktor.http.client"
        lateinit var registry: MeterRegistry
        var staticPath: String? = null
        var canonicalizer: ((String) -> String)? = null

        internal fun isRegistryInitialized() = this::registry.isInitialized
    }

    private fun before(context: HttpRequestBuilder) {
        val rawPath = staticPath ?: context.url.encodedPath
        val canonicalPath = canonicalizer?.invoke(rawPath) ?: rawPath
        context.attributes.put(
            measureKey,
            ClientCallMeasure(Timer.start(registry), canonicalPath)
        )
    }

    private fun after(call: HttpClientCall, context: HttpRequestBuilder) {
        val measure = call.attributes.getOrNull(measureKey) ?: return

        measure.timer.stop(
            Timer.builder(requestTimeTimerName).tags(
                listOf(
                    Tag.of("method", call.request.method.value),
                    Tag.of(
                        "url",
                        "${context.url.protocol.name}://${context.url.host}:${context.url.port}${measure.path}"
                    ),
                    Tag.of("status", call.response.status.value.toString()),
                )
            ).register(registry)
        )
    }

    /**
     * Companion object for feature installation
     */
    @Suppress("EXPERIMENTAL_API_USAGE_FUTURE_ERROR")
    companion object Feature : HttpClientPlugin<Config, HttpClientMetricsFeature> {
        private var clientName: String = "ktor.http.client"

        val requestTimeTimerName: String
            get() = "$clientName.requests"

        private val measureKey = AttributeKey<ClientCallMeasure>("HttpClientMetricsFeature")
        override val key: AttributeKey<HttpClientMetricsFeature> = AttributeKey("HttpClientMetricsFeature")

        override fun prepare(block: Config.() -> Unit): HttpClientMetricsFeature =
            Config().apply(block).let {
                if (!it.isRegistryInitialized()) {
                    throw IllegalArgumentException(
                        "Meter registry is missing. Please initialize the field 'registry'"
                    )
                }
                HttpClientMetricsFeature(it.registry, it.clientName, it.staticPath, it.canonicalizer)
            }

        override fun install(plugin: HttpClientMetricsFeature, scope: HttpClient) {
            clientName = plugin.clientName

            scope.plugin(HttpSend).intercept { context ->
                plugin.before(context)

                val origin = execute(context)
                plugin.after(origin, context)

                origin
            }
        }
    }

}

private data class ClientCallMeasure(
    val timer: Timer.Sample,
    val path: String,
)

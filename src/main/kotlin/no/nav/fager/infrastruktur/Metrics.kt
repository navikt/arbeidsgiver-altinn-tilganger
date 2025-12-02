package no.nav.fager.infrastruktur

import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.util.concurrent.TimeUnit

object Metrics {
    val clock: Clock = Clock.SYSTEM

    val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    /**
     * Hent en Counter med gitt navn og tags
     * eksempel:
     * ```kotlin
     * val counter = Metrics.counter("my_metric", "tag1" to "value1", "tag2" to "value2")
     * counter.increment()
     * ```
     */
    fun counter(name: String, vararg tags: Pair<String, String>): Counter =
        meterRegistry.counter(
            name,
            Tags.of(tags.map { Tag.of(it.first, it.second) })
        )

    /**
     * Inkrementer en Counter med gitt navn og tags
     * eksempel:
     * ```kotlin
     * Metrics.increment("my_metric", "tag1" to "value1", "tag2" to "value2")
     * ```
     */
    fun increment(name: String, vararg tags: Pair<String, String>) {
        counter(name, *tags).increment()
    }

    /**
     * Hent en CounterHolder for gitt navn. Denne gjør det mulig å inkrementere og sette tags samtidig.
     * eksempel:
     * ```kotlin
     * val counter = Metrics.counter("my_metric")
     * counter.increment("tag1" to "value1", "tag2" to "value2")
     * ```
     */
    fun counter(name: String) = CounterHolder(name)

    class CounterHolder(private val name: String) {
        fun increment(vararg tags: Pair<String, String>) {
            counter(name, *tags).increment()
        }
    }
}

suspend fun <T> Timer.coRecord(body: suspend () -> T): T {
    val start = Metrics.clock.monotonicTime()
    try {
        return body()
    } catch (t: Throwable) {
        Timer.builder(this.id.name)
            .tags(this.id.tags)
            .tag("throwable", t.javaClass.canonicalName)
            .register(Metrics.meterRegistry)
            .record(Metrics.clock.monotonicTime() - start, TimeUnit.NANOSECONDS)
        throw t
    } finally {
        val end = Metrics.clock.monotonicTime()
        this.record(end - start, TimeUnit.NANOSECONDS)
    }
}
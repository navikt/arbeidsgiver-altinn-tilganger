package no.nav.fager

import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

object Metrics {
    val clock: Clock = Clock.SYSTEM

    val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
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
package no.nav.fager.infrastruktur

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class MetricsTest {

    private fun metricName(base: String = "metric"): String =
        "$base.${Random.nextInt(1_000_000)}"

    @Test
    fun `increment counter directly with name and tags`() {
        val metricName = metricName()
        Metrics.increment(metricName, "result" to "success")
        Metrics.increment(metricName, "result" to "success")
        Metrics.increment(metricName, "result" to "error")

        val successCounter = Metrics.meterRegistry.find(metricName)
            .tags("result", "success")
            .counter()!!
        val errorCounter = Metrics.meterRegistry.find(metricName)
            .tags("result", "error")
            .counter()!!

        assertEquals(2.0, successCounter.count())
        assertEquals(1.0, errorCounter.count())
    }

    @Test
    fun `use counter helper to get Counter instance and increment`() {
        val metricName = metricName()
        val counter = Metrics.counter(metricName)
        counter.increment("result" to "success")
        counter.increment("result" to "success")
        counter.increment("result" to "error")

        val successCounter = Metrics.meterRegistry.find(metricName)
            .tags("result", "success")
            .counter()!!
        val errorCounter = Metrics.meterRegistry.find(metricName)
            .tags("result", "error")
            .counter()!!

        assertEquals(2.0, successCounter.count())
        assertEquals(1.0, errorCounter.count())
    }

    @Test
    fun `counter helper without tags still works`() {
        val metricName = metricName()
        val counter = Metrics.counter(metricName)
        counter.increment()
        counter.increment()

        val c = Metrics.meterRegistry.find(metricName).counter()!!
        assertEquals(2.0, c.count())
    }

    @Test
    fun `multiple tag combinations are separate counters`() {
        val metricName = metricName()
        Metrics.increment(metricName, "result" to "ok", "env" to "prod")
        Metrics.increment(metricName, "result" to "ok", "env" to "test")
        Metrics.increment(metricName, "result" to "fail", "env" to "prod")

        val c1 = Metrics.meterRegistry.find(metricName)
            .tags("result", "ok", "env", "prod").counter()!!
        val c2 = Metrics.meterRegistry.find(metricName)
            .tags("result", "ok", "env", "test").counter()!!
        val c3 = Metrics.meterRegistry.find(metricName)
            .tags("result", "fail", "env", "prod").counter()!!

        assertEquals(1.0, c1.count())
        assertEquals(1.0, c2.count())
        assertEquals(1.0, c3.count())
    }

    @Test
    fun `multiple CounterHolder instances for same metric name work correctly`() {
        val metricName = metricName()
        val counter1 = Metrics.counter(metricName)
        val counter2 = Metrics.counter(metricName)

        counter1.increment("tag" to "1")
        counter2.increment("tag" to "2")

        val c1 = Metrics.meterRegistry.find(metricName).tags("tag", "1").counter()!!
        val c2 = Metrics.meterRegistry.find(metricName).tags("tag", "2").counter()!!

        assertEquals(1.0, c1.count())
        assertEquals(1.0, c2.count())
    }
}
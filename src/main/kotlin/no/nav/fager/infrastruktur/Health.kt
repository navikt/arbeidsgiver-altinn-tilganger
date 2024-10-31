package no.nav.fager.infrastruktur

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

interface RequiresReady {
    fun isReady(): Boolean
}

object Health {
    private val log = logger()

    private val reaquiredServices = mutableListOf<RequiresReady>()

    fun register(requiresReady: RequiresReady) {
        reaquiredServices.add(requiresReady)
    }

    val alive
        get() = true

    val ready
        get() = reaquiredServices.all { it.isReady() }

    private val terminatingAtomic = AtomicBoolean(false)

    val terminating: Boolean
        get() = !alive || terminatingAtomic.get()

    init {
        val shutdownTimeout = basedOnEnv(
            prod = { Duration.ofSeconds(20) },
            dev = { Duration.ofSeconds(20) },
            other = { Duration.ofMillis(0) },
        )

        Runtime.getRuntime().addShutdownHook(object : Thread() {
            override fun run() {
                terminatingAtomic.set(true)
                log.info("shutdown signal received")
                try {
                    sleep(shutdownTimeout.toMillis())
                } catch (e: Exception) {
                    // nothing to do
                }
            }
        })
    }
}
package no.nav.fager.infrastruktur

import java.util.concurrent.atomic.AtomicBoolean

interface RequiresReady {
    fun isReady(): Boolean
}

object Health {
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

    fun terminate() {
        terminatingAtomic.set(true)
    }
}
package no.nav.fager.infrastruktur

import java.util.concurrent.atomic.AtomicBoolean

interface RequiresReady {
    fun isReady(): Boolean
}

object Health {
    internal val requiredServices = mutableListOf<RequiresReady>()

    fun register(requiresReady: RequiresReady) {
        requiredServices.add(requiresReady)
    }

    val alive
        get() = true

    val ready
        get() = requiredServices.all { it.isReady() }

    private val terminatingAtomic = AtomicBoolean(false)

    val terminating: Boolean
        get() = !alive || terminatingAtomic.get()

    fun terminate() {
        terminatingAtomic.set(true)
    }
}
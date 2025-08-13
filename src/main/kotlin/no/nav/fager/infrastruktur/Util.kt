package no.nav.fager.infrastruktur

import kotlin.coroutines.cancellation.CancellationException

fun Exception.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
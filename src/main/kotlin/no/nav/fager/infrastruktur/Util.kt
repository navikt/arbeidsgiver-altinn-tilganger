package no.nav.fager.infrastruktur

import kotlin.coroutines.cancellation.CancellationException

/**
 * Throws this exception if it is a [CancellationException], preserving coroutine cancellation signals.
 *
 * This is important in coroutine exception handling to ensure that cancellation is properly propagated,
 * and not accidentally swallowed or ignored.
 */
fun Exception.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
package no.nav.fager.executable

import no.nav.fager.fakes.FakeApplication

fun main() {
    FakeApplication(port = 8080).start(wait = true)
}

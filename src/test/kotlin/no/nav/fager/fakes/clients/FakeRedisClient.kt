package no.nav.fager.fakes.clients

import no.nav.fager.AltinnService
import no.nav.fager.AltinnTilgangerRedisClient
import no.nav.fager.fakes.FakeClientBase

class FakeRedisClient(
    private val getHandler: () -> AltinnService.AltinnTilgangerResultat? = { null },
) : AltinnTilgangerRedisClient, FakeClientBase() {

    override suspend fun get(fnr: String): AltinnService.AltinnTilgangerResultat? {
        addFunctionCall(this::get.name, fnr)
        return getHandler()
    }

    override suspend fun set(fnr: String, altinnTilganger: AltinnService.AltinnTilgangerResultat) {
        addFunctionCall(this::set.name, fnr, altinnTilganger)
    }
}
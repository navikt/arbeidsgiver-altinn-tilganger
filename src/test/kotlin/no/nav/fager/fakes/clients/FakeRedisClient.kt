package no.nav.fager.fakes.clients

import no.nav.fager.AltinnService
import no.nav.fager.AltinnTilgangerRedisClient
import no.nav.fager.fakes.FakeClientBase

class FakeRedisClient(
    private val getHandler: () -> AltinnService.AltinnTilgangerResultat? = { null },
) : AltinnTilgangerRedisClient, FakeClientBase() {

    override suspend fun get(key: String): AltinnService.AltinnTilgangerResultat? {
        addFunctionCall(this::get.name, key)
        return getHandler()
    }

    override suspend fun set(key: String, altinnTilganger: AltinnService.AltinnTilgangerResultat) {
        addFunctionCall(this::set.name, key, altinnTilganger)
    }
}
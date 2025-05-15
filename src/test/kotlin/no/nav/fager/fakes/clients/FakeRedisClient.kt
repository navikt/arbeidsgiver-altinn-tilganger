package no.nav.fager.fakes.clients

import no.nav.fager.altinn.AltinnService
import no.nav.fager.redis.AltinnTilgangerRedisClient
import no.nav.fager.fakes.FakeClientBase

class FakeRedisClient(
    private val cache: MutableMap<String, AltinnService.AltinnTilgangerResultat> = mutableMapOf(),
) : AltinnTilgangerRedisClient, FakeClientBase() {

    override suspend fun get(cacheKey: String): AltinnService.AltinnTilgangerResultat? {
        addFunctionCall(this::get.name, cacheKey)
        return cache[cacheKey]
    }

    override suspend fun set(cacheKey: String, altinnTilganger: AltinnService.AltinnTilgangerResultat) {
        addFunctionCall(this::set.name, cacheKey, altinnTilganger)
        cache[cacheKey] = altinnTilganger
    }
}
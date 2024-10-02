package no.nav.fager

import kotlinx.coroutines.runBlocking
import no.nav.fager.altinn.AltinnService
import no.nav.fager.altinn.AltinnTilgang
import no.nav.fager.redis.AltinnTilgangerRedisClientImpl
import no.nav.fager.redis.RedisConfig
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull


class RedisIntegrationTest {
    @Test
    fun `cache codec serdes works with redis server`() = runBlocking {
        val redis = AltinnTilgangerRedisClientImpl(RedisConfig.local())
        assertNull(redis.get(fakeFnr))
        redis.set(fakeFnr, altinnTilganger)
        assertEquals(altinnTilganger, redis.get(fakeFnr))
    }
}

private val fakeFnr = UUID.randomUUID().toString()
private val altinnTilganger = AltinnService.AltinnTilgangerResultat(
    isError = false,
    altinnTilganger = listOf(
        AltinnTilgang(
            orgNr = "1",
            altinn3Tilganger = setOf("test-fager"),
            altinn2Tilganger = setOf("4936:1"),
            underenheter = listOf(
                AltinnTilgang(
                    orgNr = "2",
                    altinn3Tilganger = setOf("test-fager"),
                    altinn2Tilganger = setOf("4936:1"),
                    underenheter = listOf(),
                    name = "Donald Duck & Co Avd. Andebyen",
                    organizationForm = "BEDR"
                )
            ),
            name = "Donald Duck & Co",
            organizationForm = "AS"
        )
    )
)

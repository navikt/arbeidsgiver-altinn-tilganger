package no.nav.fager

import io.valkey.params.SetParams
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import no.nav.fager.altinn.AltinnService.AltinnTilgangerResultat
import no.nav.fager.redis.*
import org.junit.jupiter.api.Assertions.assertFalse
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue


class RedisIntegrationTest {
    @Test
    fun `cache codec serdes works with redis server`() = runBlocking {
        val redis = AltinnTilgangerRedisClientImpl(RedisConfig.local())
        assertNull(redis.get(fakeFnr))
        redis.set(fakeFnr, altinnTilganger)
        assertEquals(altinnTilganger, redis.get(fakeFnr))
    }

    @Test
    fun `corrupt entry is cleared and reloaded`() = runBlocking {
        @Serializable
        class Foo(val foo: String, val bar: String)

        val freshEntity = Foo("foo", "bar")
        var loaderCalled = false

        val cache = RedisLoadingCache(
            name = "foo",
            redisClient = RedisConfig.local().createClient<Foo>("foo"),
            loader = {
                loaderCalled = true
                freshEntity
            },
        )
        assertFalse(loaderCalled, "loader should not be called yet")

        // prime redis with corrupt entry
        RedisConfig.local().createClient<String>("foo")
            .set("foo", """{"who": "not foo"}""", SetParams().ex(10))

        // read entry
        val actual = cache.get("foo")
        assertEquals(freshEntity, actual)
        assertTrue(loaderCalled, "loader should be called")
    }
}

private val fakeFnr = UUID.randomUUID().toString()
private val altinnTilganger = AltinnTilgangerResultat(
    isError = false,
    altinnTilganger = listOf(
        AltinnTilgang(
            orgnr = "1",
            altinn3Tilganger = setOf("test-fager"),
            altinn2Tilganger = setOf("4936:1"),
            underenheter = listOf(
                AltinnTilgang(
                    orgnr = "2",
                    altinn3Tilganger = setOf("test-fager"),
                    altinn2Tilganger = setOf("4936:1"),
                    underenheter = listOf(),
                    navn = "Donald Duck & Co Avd. Andebyen",
                    organisasjonsform = "BEDR",
                    erSlettet = false
                )
            ),
            navn = "Donald Duck & Co",
            organisasjonsform = "AS",
            erSlettet = false
        )
    )
)

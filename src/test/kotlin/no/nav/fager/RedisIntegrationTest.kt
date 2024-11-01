package no.nav.fager

import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.codec.StringCodec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import no.nav.fager.altinn.AltinnService
import no.nav.fager.altinn.AltinnService.AltinnTilgangerResultat
import no.nav.fager.altinn.AltinnTilgang
import no.nav.fager.redis.*
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

    @OptIn(ExperimentalLettuceCoroutinesApi::class)
    @Test
    fun `corrupt entry is cleared and reloaded`() = runBlocking {
        @Serializable
        class Foo(val foo: String, val bar: String)

        val freshEntity = Foo("foo", "bar")

        val cache = RedisLoadingCache(
            name = "foo",
            redisClient = RedisConfig.local().createClient(),
            codec = createCodec<Foo>("foo"),
            loader = { freshEntity },
        )

        // prime redis with corrupt entry
        RedisConfig.local().createClient().useConnection(StringCodec.UTF8) {
            it.set("foo:foo", """{"who": "not foo"}""")
        }

        // read entry
        assertEquals(freshEntity, cache.get("foo"))
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
                    organisasjonsform = "BEDR"
                )
            ),
            navn = "Donald Duck & Co",
            organisasjonsform = "AS"
        )
    )
)

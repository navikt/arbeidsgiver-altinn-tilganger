package no.nav.fager.redis

import io.lettuce.core.*
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.codec.StringCodec
import io.micrometer.core.instrument.Counter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.nav.fager.altinn.AltinnService.AltinnTilgangerResultat
import no.nav.fager.infrastruktur.Metrics
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration

class RedisConfig(
    val uri: String,
    val username: String,
    val password: String,
) {
    /* Pass på at vi ikke printer ut `password`. */
    override fun toString() = "RedisConfig(uri=$uri, username=$username, password=SECRET)"

    companion object {
        fun nais() = RedisConfig(
            uri = System.getenv("REDIS_URI_TILGANGER"),
            username = System.getenv("REDIS_USERNAME_TILGANGER"),
            password = System.getenv("REDIS_PASSWORD_TILGANGER"),
        )

        fun local() = RedisConfig(
            uri = "redis://127.0.0.1:6379",
            username = "",
            password = "123",
        )
    }

    fun createClient(): RedisClient {
        val redisURI = RedisURI.create(uri)
        redisURI.credentialsProvider = StaticCredentialsProvider(username, password.toCharArray())
        return RedisClient.create(redisURI)
    }
}

interface AltinnTilgangerRedisClient {
    suspend fun get(fnr: String): AltinnTilgangerResultat?
    suspend fun set(fnr: String, altinnTilganger: AltinnTilgangerResultat)
}

class AltinnTilgangerRedisClientImpl(redisConfig: RedisConfig) : AltinnTilgangerRedisClient {
    private val codec = createCodec<AltinnTilgangerResultat>()
    private val redisClient = redisConfig.createClient()

    @OptIn(ExperimentalLettuceCoroutinesApi::class)
    override suspend fun get(fnr: String): AltinnTilgangerResultat? {
        return redisClient.useConnection(codec) {
            it.get("altinn-tilganger:${fnr.hashed()}")
        }
    }

    @OptIn(ExperimentalLettuceCoroutinesApi::class)
    override suspend fun set(fnr: String, altinnTilganger: AltinnTilgangerResultat) {
        redisClient.useConnection(codec) {
            it.set(
                "altinn-tilganger:${fnr.hashed()}",
                altinnTilganger,
                SetArgs.Builder.ex(
                    Duration.ofMinutes(10)
                )
            )
            altinnTilganger
        }
    }
}

inline fun <reified T> createCodec(): RedisCodec<String, T> {
    return object : RedisCodec<String, T> {
        private val stringCodec = StringCodec.UTF8

        override fun decodeKey(bytes: ByteBuffer): String = stringCodec.decodeKey(bytes)

        override fun decodeValue(bytes: ByteBuffer): T =
            stringCodec.decodeValue(bytes).let {
                Json.decodeFromString(it)
            }

        override fun encodeKey(key: String): ByteBuffer = stringCodec.encodeKey(key)

        override fun encodeValue(value: T): ByteBuffer =
            Json.encodeToString(value).let {
                stringCodec.encodeValue(it)
            }
    }
}

private fun String.hashed() =
    String(MessageDigest.getInstance("SHA-256").digest(this.toByteArray()), charset("UTF-8"))

@OptIn(ExperimentalLettuceCoroutinesApi::class)
suspend fun <T : Any> RedisClient.useConnection(
    codec: RedisCodec<String, T>,
    body: suspend (RedisCoroutinesCommands<String, T>) -> T?
): T? {
    return this.connect(codec).use { connection ->
        val api = connection.coroutines()
        body(api)
    }
}

class RedisLoadingCache<T : Any>(
    private val name: String,
    private val redisClient: RedisClient,
    private val codec: RedisCodec<String, T>,
    private val loader: suspend (String) -> T,
    private val cacheTTL: Duration? = Duration.ofMinutes(10),
) {
    private val cacheHit = Counter.builder(name).tag("result", "hit").register(Metrics.meterRegistry)
    private val cacheMiss = Counter.builder(name).tag("result", "miss").register(Metrics.meterRegistry)

    @OptIn(ExperimentalLettuceCoroutinesApi::class)
    suspend fun get(key: String): T {
        val redisKey = "$name:${key.hashed()}" // TODO: should the namespacing be done in the codec?
        return redisClient.useConnection(codec) { connection ->
            connection.get(redisKey)?.also {
                cacheHit.increment()
            } ?: run {
                cacheMiss.increment()
                loader(key).also {
                    connection.set(redisKey, it, SetArgs.Builder.ex(cacheTTL))
                }
            }
        } ?: throw IllegalStateException("Failed to get value from cache")
    }
}


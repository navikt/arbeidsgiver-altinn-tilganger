package no.nav.fager.redis

import io.micrometer.core.instrument.Counter
import io.valkey.DefaultJedisClientConfig
import io.valkey.HostAndPort
import io.valkey.JedisPool
import io.valkey.JedisPoolConfig
import io.valkey.params.SetParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import no.nav.fager.altinn.AltinnService.AltinnTilgangerResultat
import no.nav.fager.infrastruktur.Metrics
import no.nav.fager.infrastruktur.logger
import no.nav.fager.infrastruktur.rethrowIfCancellation
import java.security.MessageDigest
import java.time.Duration

class RedisConfig(
    val pool: JedisPool
) {

    companion object {
        fun nais() = RedisConfig(
            JedisPool(
                JedisPoolConfig(),
                HostAndPort(
                    System.getenv("VALKEY_HOST_TILGANGER"),
                    System.getenv("VALKEY_PORT_TILGANGER").toInt()
                ),
                DefaultJedisClientConfig.builder()
                    .user(System.getenv("VALKEY_USERNAME_TILGANGER"))
                    .password(System.getenv("VALKEY_PASSWORD_TILGANGER"))
                    .ssl(true)
                    .build()
            )
        )

        fun local() = RedisConfig(
            JedisPool(
                JedisPoolConfig(),
                /* host */ "127.0.0.1",
                /* port */ 6379,
                /* timeout */ 0,
                /* password */ "123",
            )
        )
    }

    inline fun <reified V> createClient(namespace: String) =
        RedisClient(pool, createCodec<V>(namespace))
}

class RedisClient<V>(
    private val jedisPool: JedisPool,
    private val codec: RedisCodec<String, V>,
) {

    suspend fun get(key: String): V? = retryOnException {
        withContext(Dispatchers.IO) {
            jedisPool.resource.use { jedis ->
                jedis.get(codec.encodeKey(key))?.let { codec.decodeValue(it) }
            }
        }
    }

    suspend fun set(key: String, value: V, params: SetParams): String = retryOnException {
        withContext(Dispatchers.IO) {
            jedisPool.resource.use { jedis ->
                jedis.set(codec.encodeKey(key), codec.encodeValue(value), params)
            }
        }
    }
}

suspend fun <T> retryOnException(
    maxAttempts: Int = 3,
    delayMillis: Long = 200,
    block: suspend () -> T
): T {
    repeat(maxAttempts - 1) {
        try {
            return block()
        } catch (e: Exception) {
            e.rethrowIfCancellation()

            delay(delayMillis)
        }
    }
    return block()
}

interface RedisCodec<K, V> {
    fun encodeKey(key: K): String
    fun decodeKey(key: String): K
    fun encodeValue(value: V): String
    fun decodeValue(value: String): V
}

interface AltinnTilgangerRedisClient {
    suspend fun get(fnr: String): AltinnTilgangerResultat?
    suspend fun set(fnr: String, altinnTilganger: AltinnTilgangerResultat)
}

class AltinnTilgangerRedisClientImpl(redisConfig: RedisConfig) : AltinnTilgangerRedisClient {
    private val redisClient = redisConfig.createClient<AltinnTilgangerResultat>("altinn-tilganger")

    override suspend fun get(fnr: String) = redisClient.get(fnr.hashed())

    override suspend fun set(fnr: String, altinnTilganger: AltinnTilgangerResultat) {
        redisClient.set(
            fnr.hashed(),
            altinnTilganger,
            SetParams().ex(Duration.ofMinutes(10).seconds)
        )
    }
}


inline fun <reified T> createCodec(prefix: String): RedisCodec<String, T> {
    return object : RedisCodec<String, T> {
        private val namespace = "$prefix:"


        override fun decodeKey(key: String): String = key.removePrefix(namespace)
        override fun encodeKey(key: String): String = "$namespace$key"

        override fun encodeValue(value: T): String = Json.encodeToString(value)
        override fun decodeValue(value: String): T = Json.decodeFromString(value)
    }
}

fun String.hashed() =
    String(MessageDigest.getInstance("SHA-256").digest(this.toByteArray()), charset("UTF-8"))


class RedisLoadingCache<T : Any>(
    name: String,
    private val redisClient: RedisClient<T>,
    private val loader: suspend (String) -> T,
    private val cacheTTL: Duration = Duration.ofMinutes(10),
) {
    private val log = logger()
    private val cacheHit = Counter.builder(name).tag("result", "hit").register(Metrics.meterRegistry)
    private val cacheError = Counter.builder(name).tag("result", "error").register(Metrics.meterRegistry)
    private val cacheMiss = Counter.builder(name).tag("result", "miss").register(Metrics.meterRegistry)

    suspend fun get(key: String): T {
        return try {
            redisClient.get(key)
        } catch (e: SerializationException) {
            cacheError.increment()
            log.error("Deserialisering av cache entry feilet. Entry vil bli reloadet", e)
            null
        }?.also {
            cacheHit.increment()
        } ?: run {
            cacheMiss.increment()
            loader(key).also {
                redisClient.set(key, it, SetParams().ex(cacheTTL.seconds))
            }
        }
    }

    suspend fun update(key: String): T =
        loader(key).also {
            redisClient.set(key, it, SetParams().ex(cacheTTL.seconds))
        }
}


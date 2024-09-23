package no.nav.fager

import io.lettuce.core.*
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.codec.StringCodec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.nav.fager.AltinnService.AltinnTilgangerResultat
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
    private val redisClient = redisConfig.createClient()

    @OptIn(ExperimentalLettuceCoroutinesApi::class)
    override suspend fun get(fnr: String): AltinnTilgangerResultat? {
        return redisClient.useConnection(AltinnTilgangerCacheCodec()) {
            it.get(fnr.hashed())
        }
    }

    @OptIn(ExperimentalLettuceCoroutinesApi::class)
    override suspend fun set(fnr: String, altinnTilganger: AltinnTilgangerResultat) {
        redisClient.useConnection(AltinnTilgangerCacheCodec()) {
            it.set(
                fnr.hashed(), altinnTilganger, SetArgs.Builder.ex(
                    Duration.ofMinutes(10)
                )
            )
            altinnTilganger
        }
    }
}

class AltinnTilgangerCacheCodec : RedisCodec<String, AltinnTilgangerResultat> {
    private val stringCodec = StringCodec.UTF8

    override fun decodeKey(bytes: ByteBuffer): String = stringCodec.decodeKey(bytes)

    override fun decodeValue(bytes: ByteBuffer): AltinnTilgangerResultat =
        stringCodec.decodeValue(bytes).let {
            Json.decodeFromString(it)
        }

    override fun encodeKey(key: String): ByteBuffer = stringCodec.encodeKey(key)

    override fun encodeValue(value: AltinnTilgangerResultat): ByteBuffer =
        Json.encodeToString(value).let {
            stringCodec.encodeValue(it)
        }

}

private fun String.hashed() =
    String(MessageDigest.getInstance("SHA-256").digest(this.toByteArray()), charset("UTF-8"))

@OptIn(ExperimentalLettuceCoroutinesApi::class)
suspend fun <T : Any> RedisClient.useConnection(
    codec: RedisCodec<String, T>, body: suspend (RedisCoroutinesCommands<String, T>) -> T?
): T? {
    return this.connect(codec).use { connection ->
        val api = connection.coroutines()
        body(api)
    }
}


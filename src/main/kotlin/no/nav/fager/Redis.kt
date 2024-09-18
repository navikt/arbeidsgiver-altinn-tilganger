package no.nav.fager

import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.SetArgs
import io.lettuce.core.StaticCredentialsProvider
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.codec.RedisCodec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import no.nav.fager.AltinnService.AltinnTilgangerResultat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
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
        return redisClient.useConnect(AltinnTilgangerCacheCodec()) {
            it.get(altinnTilgangerCacheKey(fnr))
        }

    }

    @OptIn(ExperimentalLettuceCoroutinesApi::class)
    override suspend fun set(fnr: String, altinnTilganger: AltinnTilgangerResultat) {
        redisClient.useConnect(AltinnTilgangerCacheCodec()) {
            it.set(
                altinnTilgangerCacheKey(fnr), altinnTilganger, SetArgs.Builder.ex(
                    Duration.ofMinutes(10)
                )
            )
            altinnTilganger
        }
    }

    private fun altinnTilgangerCacheKey(fnr: String): String {
        val fnrHash = sha256(fnr)
        return "altinn-tilganger:$fnrHash"
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
}

class AltinnTilgangerCacheCodec : RedisCodec<String, AltinnTilgangerResultat> {
    private val charset: Charset = Charset.forName("UTF-8")

    override fun decodeKey(bytes: ByteBuffer): String {
        return charset.decode(bytes).toString()
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun decodeValue(bytes: ByteBuffer): AltinnTilgangerResultat? {
        try {
            val tilganger = Json.decodeFromStream<AltinnTilgangerResultat>(ByteArrayInputStream(bytes.array()))
            return tilganger
        } catch (e: Exception) {
            return null
        }
    }

    override fun encodeKey(key: String): ByteBuffer {
        return charset.encode(key)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun encodeValue(value: AltinnTilgangerResultat?): ByteBuffer {
        try {
            val stream = ByteArrayOutputStream()
            Json.encodeToStream(value, stream)
            return ByteBuffer.wrap(stream.toByteArray())
        } catch (e: IOException) {
            return ByteBuffer.wrap(ByteArray(0))
        }
    }
}


@OptIn(ExperimentalLettuceCoroutinesApi::class)
suspend fun <T> RedisClient.useConnect(
    body: suspend (RedisCoroutinesCommands<String, String>) -> T
): T {
    return this.connect().use { connection ->
        val api = connection.coroutines()
        body(api)
    }
}

@OptIn(ExperimentalLettuceCoroutinesApi::class)
suspend fun <T : Any> RedisClient.useConnect(
    codec: RedisCodec<String, T>, body: suspend (RedisCoroutinesCommands<String, T>) -> T?
): T? {
    return this.connect(codec).use { connection ->
        val api = connection.coroutines()
        body(api)
    }
}


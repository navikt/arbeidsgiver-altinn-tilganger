package no.nav.fager

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import kotlinx.coroutines.runBlocking
import no.nav.fager.fakes.FakeApplication
import kotlin.test.Test
import kotlin.test.assertEquals

val localRedisConfig = RedisConfig(
    uri = "redis://127.0.0.1:6379",
    username = "",
    password = "123",
)

@OptIn(ExperimentalLettuceCoroutinesApi::class)
class RedisTest {
    companion object {
        @JvmField
        @org.junit.ClassRule
        val app = FakeApplication {
            install(ContentNegotiation) {
                json()
            }
        }
    }

    @Test
    fun redisClientTest() {
        runBlocking {
            val redisClient = localRedisConfig.createClient()
            redisClient.useConnect { connection ->
                connection.set("key", "value")
                assertEquals("value", connection.get("key"))
            }
        }
    }

    @Test
    fun `Cache to redis via Altinn Tilganger`() = app.runTest {
        val keyValue = Pair("key", "value")

        client.post("/SetCache") {
            contentType(ContentType.Application.Json)
            setBody(
                "{" +
                        "\"key\": \"${keyValue.first}\"," +
                        "\"value\": \"${keyValue.second}\"" +
                        "}".trimIndent()
            )
        }

        val response = client.post("/GetCache") {
            contentType(ContentType.Application.Json)
            setBody("""{"key":"${keyValue.first}"}""")
        }

        val retrievedCache = response.body<GetValue>()

        assertEquals(keyValue.second, retrievedCache.value)
        assertEquals(response.status.value, 200)


        val response2 = client.post("/GetCache") {
            contentType(ContentType.Application.Json)
            setBody("""{"key":"foo"}""")
        }

        assertEquals(null, response2.body<GetValue>().value)
    }
}
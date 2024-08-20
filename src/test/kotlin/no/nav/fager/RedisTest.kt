package no.nav.fager

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.RedisClient
import io.lettuce.core.api.coroutines
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals


@OptIn(ExperimentalLettuceCoroutinesApi::class)
class RedisTest {
    @Test
    fun redisClientTest() {
        runBlocking {
            val redisClient = RedisClient.create("redis://localhost:6379")
            redisClient.connect().use { connection ->
                val api = connection.coroutines()
                api.set("key", "value")
                assertEquals("value", api.get("key"))
            }
        }
    }

    @Test
    fun `Cache to redis via Altinn Tilganger`() = testApplication {
        application {
            ktorConfig(authConfig = oauth2MockServer, maskinportenConfig = maskinportenMockConfig)
        }
        val keyValue = Pair("key", "value")

        val postedCache = client.post("/SetCache") {
            contentType(ContentType.Application.Json)
            setBody(
                "{" +
                        "\"key\": \"${keyValue.first}\"," +
                        "\"value\": \"${keyValue.second}\"" +
                        "}".trimIndent()
            )
        }

        val response = client.post("/GetCache"){
            contentType(ContentType.Application.Json)
            setBody("""{"key":"${keyValue.first}"}""")
        }
        val retrievedCache = response.body<GetValue>()

        assertEquals(keyValue.second, retrievedCache.value)
        assertEquals(response.status.value,  200)

        val response2 = client.post("/GetCache"){
            contentType(ContentType.Application.Json)
            setBody("""{"key":"foo"}""")
        }

        assertEquals(null, response2.body<GetValue>().value)

    }
}
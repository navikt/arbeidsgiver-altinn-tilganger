package no.nav.fager

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

val localRedisConfig = RedisConfig(
    uri = "redis://127.0.0.1:6379",
    username = "",
    password = "123",
)

@OptIn(ExperimentalLettuceCoroutinesApi::class)
class RedisTest {
    @Test
    fun redisClientTest() {
        runBlocking {
            val redisClient = localRedisConfig.createClient()
            redisClient.connect { connection ->
                connection.set("key", "value")
                assertEquals("value", connection.get("key"))
            }
        }
    }

    @Test
    fun `Cache to redis via Altinn Tilganger`() = testApplication {
        application {
            mockKtorConfig()
        }
        val client = createClient {
            install(ContentNegotiation) {
                json()
            }
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
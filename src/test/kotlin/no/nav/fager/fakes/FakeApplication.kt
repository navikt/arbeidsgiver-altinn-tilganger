package no.nav.fager.fakes

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIOEngineConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import no.nav.fager.altinn.Altinn2Config
import no.nav.fager.altinn.Altinn3Config
import no.nav.fager.altinn.KnownResourceIds
import no.nav.fager.ktorConfig
import no.nav.fager.redis.RedisConfig
import no.nav.fager.texas.IdentityProvider
import no.nav.fager.texas.TexasAuthConfig

private fun resolveMockToken(token: String?): Map<String, String> {
    return if (token == null || token.count { it == ':' } != 1) {
        emptyMap()
    } else {
        val (acr, pid) = token.split(":")
        mapOf(
            "pid" to pid,
            "acr" to acr,
            "client_id" to "local:test",
        )
    }
}

class FakeApplication(
    private val port: Int = 0,
    private val clientConfig: HttpClientConfig<CIOEngineConfig>.() -> Unit = {}
) : AutoCloseable {

    private val fakeAltinn3Api = FakeApi().also {
        KnownResourceIds.forEach { resourceId ->
            val response = when (resourceId) {
                "nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger" ->
                    //language=json
                    """
                    {
                      "links": {},
                      "data": [
                        {
                          "type": "urn:altinn:rolecode",
                          "value": "dagl",
                          "urn": "urn:altinn:rolecode:dagl"
                        },
                        {
                          "type": "urn:altinn:rolecode",
                          "value": "lede",
                          "urn": "urn:altinn:rolecode:lede"
                        }
                      ]
                    }
                    """.trimIndent()

                else ->
                    //language=json
                    """{ "links": {}, "data": [] }"""
            }
            it.stubs[Get to "/resourceregistry/api/v1/resource/${resourceId}/policy/subjects"] = {
                call.respondText(response, ContentType.Application.Json)
            }
        }
    }
    private val fakeAltinn2Api = FakeApi()
    private val fakeTexas = FakeApi().also {
        it.stubs[Post to "/token"] = {
            val contentType = call.request.contentType()
            require(contentType.match(ContentType.Application.FormUrlEncoded)) {
                "expected content-type application/x-www-form-urlencoded, got $contentType"
            }
            val form = call.receiveParameters()

            // form["target"] // = requested scope
            if (form["identity_provider"] == IdentityProvider.MASKINPORTEN.alias) {
                call.respondText(
                    """
                    {
                        "access_token": "fake_maskinporten_token",
                        "expires_in": 3599,
                        "token_type": "Bearer"
                    }    
                    """.trimIndent(),
                    ContentType.Application.Json
                )
            }
        }

        it.stubs[Post to "/introspect"] = {
            val contentType = call.request.contentType()
            require(contentType.match(ContentType.Application.FormUrlEncoded)) {
                "expected content-type application/x-www-form-urlencoded, got $contentType"
            }
            val form = call.receiveParameters()

            // azure = m2m, bare mocke at man har gyldig m2m token
            if (form["identity_provider"] == IdentityProvider.AZURE_AD.alias) {
                call.respondText(
                    //language=json
                    """
                    {
                        "active": true,
                        "azp_name": "test:fager:test-client"
                    }    
                    """.trimIndent(),
                    ContentType.Application.Json
                )
            }

            // tokenx = obo, mocke gyldig obo token
            if (form["identity_provider"] == IdentityProvider.TOKEN_X.alias) {
                val token = resolveMockToken(form["token"])

                call.respond(
                    HashMap(
                        mapOf(
                            "active" to token.isNotEmpty().toString(),
                        ) + token
                    )
                )
            }
        }
    }

    private val server by lazy {
        embeddedServer(CIO, port = port, host = "0.0.0.0", module = {
            ktorConfig(
                altinn3Config = Altinn3Config(
                    baseUrl = "http://localhost:${fakeAltinn3Api.port}",
                    ocpApimSubscriptionKey = "someSubscriptionKey",
                ),
                altinn2Config = Altinn2Config(
                    baseUrl = "http://localhost:${fakeAltinn2Api.port}",
                    apiKey = "someApiKey",
                ),
                redisConfig = RedisConfig.local(),
                texasAuthConfig = TexasAuthConfig.fake(fakeTexas),
            )
        })
    }

    private var testContext: TestContext? = null

    fun start(wait: Boolean = false) = runBlocking {
        fakeTexas.start()
        fakeAltinn3Api.start()
        fakeAltinn2Api.start()

        server.start(wait) // waits until killed
    }


    class TestContext(
        val client: HttpClient,
    )

    fun runTest(body: suspend TestContext.() -> Unit) = runBlocking {
        testContext!!.body()
        fakeTexas.assertNoErrors()
        fakeAltinn3Api.assertNoErrors()
        fakeAltinn2Api.assertNoErrors()
    }

    fun altinn3Response(
        httpMethod: HttpMethod,
        path: String,
        handlePost: (suspend RoutingContext.(Any) -> Unit)
    ) {
        fakeAltinn3Api.stubs[httpMethod to path] = handlePost
        fakeAltinn3Api.errors.clear()
    }

    fun altinn2Response(
        httpMethod: HttpMethod,
        path: String,
        handlePost: (suspend RoutingContext.(Any) -> Unit)
    ) {
        fakeAltinn2Api.stubs[httpMethod to path] = handlePost
        fakeAltinn2Api.errors.clear()
    }

    fun texasResponse(
        httpMethod: HttpMethod,
        path: String,
        handlePost: (suspend RoutingContext.(Any) -> Unit)
    ) {
        fakeTexas.stubs[httpMethod to path] = handlePost
        fakeTexas.errors.clear()
    }

    fun setupTestContext() = runBlocking {
        start(false)
        server.engine.waitUntilReady()
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(io.ktor.client.engine.cio.CIO) {
            defaultRequest {
                url("http://127.0.0.1:$port/")
            }
            clientConfig()
        }
        testContext = TestContext(client)
    }

    override fun close() {
        server.stop()
        fakeTexas.stop()
        fakeAltinn3Api.stop()
        fakeAltinn2Api.stop()
    }
}

fun testWithFakeApplication(
    block: suspend FakeApplication.TestContext.(fakeApplication: FakeApplication) -> Unit
) = FakeApplication(
    clientConfig = {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
).use { fakeApp ->
    fakeApp.setupTestContext()
    fakeApp.runTest {
        block(fakeApp)
    }
}
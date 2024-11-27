package no.nav.fager.fakes

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.runBlocking
import no.nav.fager.altinn.Altinn2Config
import no.nav.fager.altinn.Altinn3Config
import no.nav.fager.ktorConfig
import no.nav.fager.redis.RedisConfig
import no.nav.fager.texas.IdentityProvider
import no.nav.fager.texas.TexasAuthConfig


class FakeApplication(
    private val port: Int = 0,
    private val clientConfig: HttpClientConfig<CIOEngineConfig>.() -> Unit = {}
) : org.junit.rules.ExternalResource() {
    private val fakeAltinn3Api = FakeApi().also {
        it.stubs[
            // når det kommer flere ressurser i KnownResources, må det legges til flere svar eller støtte for wildcards i fakeapi
            Get to "/resourceregistry/api/v1/resource/nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-meldinger/policy/subjects"
        ] = {
            call.respondText(
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
                """.trimIndent(), ContentType.Application.Json
            )
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
                val token = mockOboTokens[form["token"]] ?: mapOf()

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

    public override fun before() {
        start(wait = false)
    }

    fun start(wait: Boolean = false) {
        fakeTexas.start()
        fakeAltinn3Api.start()
        fakeAltinn2Api.start()
        server.start(wait = wait)
        server.waitUntilReady()

        val port = runBlocking {
            server.resolvedConnectors().first().port
        }

        val client = HttpClient(io.ktor.client.engine.cio.CIO) {
            defaultRequest {
                url("http://localhost:$port/")
            }
            clientConfig()
        }

        testContext = TestContext(client)
    }

    public override fun after() {
        server.stop()
        fakeTexas.stop()
        fakeAltinn3Api.stop()
        fakeAltinn2Api.stop()
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
        handlePost: (suspend PipelineContext<Unit, ApplicationCall>.(Any) -> Unit)
    ) {
        fakeAltinn3Api.stubs[httpMethod to path] = handlePost
        fakeAltinn3Api.errors.clear()
    }

    fun altinn2Response(
        httpMethod: HttpMethod,
        path: String,
        handlePost: (suspend PipelineContext<Unit, ApplicationCall>.(Any) -> Unit)
    ) {
        fakeAltinn2Api.stubs[httpMethod to path] = handlePost
        fakeAltinn2Api.errors.clear()
    }

    fun texasResponse(
        httpMethod: HttpMethod,
        path: String,
        handlePost: (suspend PipelineContext<Unit, ApplicationCall>.(Any) -> Unit)
    ) {
        fakeTexas.stubs[httpMethod to path] = handlePost
        fakeTexas.errors.clear()
    }
}

val mockOboTokens = mapOf(
    "acr-high-11111111111" to mapOf(
        "pid" to "11111111111",
        "acr" to "idporten-loa-high",
        "client_id" to "local:test",
    ),
    "acr-high-22222222222" to mapOf(
        "pid" to "22222222222",
        "acr" to "idporten-loa-high",
        "client_id" to "local:test",
    ),
    "acr-low-33333333333" to mapOf(
        "pid" to "33333333333",
        "acr" to "idporten-loa-low",
        "client_id" to "local:test",
    ),
//    trenger ikke teste audience validering da dette håndteres i texas
//    "wrong-audience-44444444444" to mapOf(
//        "active" to false,
//        "pid" to "44444444444",
//        "acr" to "idporten-loa-high",
//    ),
)
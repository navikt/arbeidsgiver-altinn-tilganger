package no.nav.fager.fakes

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIOEngineConfig
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.*
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.*
import io.ktor.util.pipeline.PipelineContext
import kotlinx.coroutines.runBlocking
import no.nav.fager.altinn.Altinn2Config
import no.nav.fager.altinn.Altinn3Config
import no.nav.fager.redis.RedisConfig
import no.nav.fager.ktorConfig
import kotlin.test.fail


class FakeApplication(
    private val port: Int = 0,
    private val clientConfig: HttpClientConfig<CIOEngineConfig>.() -> Unit = {}
) : org.junit.rules.ExternalResource() {
    private val fakeAltinn3Api = FakeApi().also {
        it.stubs[
            // når det kommer flere ressurser i KnownResources, må det legges til flere svar eller støtte for wildcards i fakeapi
            Get to "/resourceregistry/api/v1/resource/nav_permittering-og-nedbemmaning_innsyn-i-alle-innsendte-skjemaer/policy/subjects"
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
    private val fakeMaskinporten = FakeMaskinporten()

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
                authConfig = oauth2MockServer,
                maskinportenConfig = fakeMaskinporten.config(),
                redisConfig = RedisConfig.local(),
            )
        })
    }

    private var testContext: TestContext? = null

    public override fun before() {
        start(wait = false)
    }

    fun start(wait: Boolean = false) {
        fakeAltinn3Api.start()
        fakeAltinn2Api.start()
        fakeMaskinporten.before()
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
        fakeMaskinporten.after()
        fakeAltinn3Api.stop()
        fakeAltinn2Api.stop()
    }

    class TestContext(
        val client: HttpClient,
    )

    fun runTest(body: suspend TestContext.() -> Unit) = runBlocking {
        testContext!!.body()
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
}

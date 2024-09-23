package no.nav.fager.fakes

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIOEngineConfig
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.util.pipeline.PipelineContext
import kotlinx.coroutines.runBlocking
import no.nav.fager.RedisConfig
import no.nav.fager.ktorConfig


class FakeApplication(
    private val port: Int = 0,
    private val clientConfig: HttpClientConfig<CIOEngineConfig>.() -> Unit = {}
) : org.junit.rules.ExternalResource() {
    private val fakeAltinn3Api = FakeAltinn3Api()
    private val fakeAltinn2Api = FakeAltinn2Api()
    private val fakeMaskinporten = FakeMaskinporten()

    private val server by lazy {
        embeddedServer(CIO, port = port, host = "0.0.0.0", module = {
            ktorConfig(
                altinn3Config = fakeAltinn3Api.config(),
                altinn2Config = fakeAltinn2Api.config(),
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
        fakeAltinn3Api.assertNoAltinnErrors()
    }

    fun altinn3Response(
        httpMethod: HttpMethod,
        path: String,
        handlePost: (suspend PipelineContext<Unit, ApplicationCall>.(Any) -> Unit)
    ) {
        fakeAltinn3Api.expectedMethod = httpMethod
        fakeAltinn3Api.expectedPath = path
        fakeAltinn3Api.handler = handlePost
        fakeAltinn3Api.error = null
    }

    fun altinn2Response(
        httpMethod: HttpMethod,
        path: String,
        handlePost: (suspend PipelineContext<Unit, ApplicationCall>.(Any) -> Unit)
    ) {
        fakeAltinn2Api.expectedMethod = httpMethod
        fakeAltinn2Api.expectedPath = path
        fakeAltinn2Api.handler = handlePost
        fakeAltinn2Api.error = null
    }
}

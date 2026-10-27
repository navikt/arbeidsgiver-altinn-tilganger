package no.nav.fager

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.response.respondText
import no.nav.fager.fakes.testWithFakeApplication
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ClientIdMdcTest {
    private val fnr = generateSequence { "${(11111111111..99999999999).random()}" }.iterator()
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = object : ListAppender<ILoggingEvent>() {
        // Logback reads MDC lazily from the calling thread, so snapshot it on the logging thread.
        override fun append(event: ILoggingEvent) {
            event.mdcPropertyMap
            super.append(event)
        }
    }

    @BeforeTest
    fun setup() {
        appender.start()
        root.addAppender(appender)
    }

    @AfterTest
    fun teardown() {
        root.detachAppender(appender)
        appender.stop()
    }

    @Test
    fun `m2m-oppslag har clientId i MDC både i handler og i kall-loggen`() {
        testWithFakeApplication { app ->
            app.altinn3Response(Post, "/accessmanagement/api/v1/resourceowner/authorizedparties") {
                call.respondText("[]", ContentType.Application.Json)
            }

            client.post("/m2m/altinn-tilganger") {
                header("Authorization", "Bearer m2m")
                contentType(ContentType.Application.Json)
                setBody("""{"fnr": "${fnr.next()}"}""")
            }.also { assertEquals(HttpStatusCode.OK, it.status) }

            assertEquals("test:fager:test-client", altinnServiceEvent().mdcPropertyMap["clientId"])
        }

        // CallLogging may write the completion line after the client has its response, so assert after shutdown.
        assertEquals(
            "test:fager:test-client",
            event { it.formattedMessage.contains("/m2m/altinn-tilganger in ") }.mdcPropertyMap["clientId"],
        )
    }

    @Test
    fun `obo-oppslag har clientId i MDC i handler`() = testWithFakeApplication { app ->
        app.altinn3Response(Post, "/accessmanagement/api/v1/resourceowner/authorizedparties") {
            call.respondText("[]", ContentType.Application.Json)
        }

        client.post("/altinn-tilganger") {
            header("Authorization", "Bearer idporten-loa-high:${fnr.next()}")
            contentType(ContentType.Application.Json)
            setBody("")
        }.also { assertEquals(HttpStatusCode.OK, it.status) }

        assertEquals("local:test", altinnServiceEvent().mdcPropertyMap["clientId"])
    }

    private fun altinnServiceEvent() = event {
        it.loggerName == "no.nav.fager.altinn.AltinnService" && it.message.startsWith("Hentet Altinn-tilganger")
    }

    private fun event(predicate: (ILoggingEvent) -> Boolean): ILoggingEvent =
        synchronized(appender) { appender.list.single(predicate) }
}

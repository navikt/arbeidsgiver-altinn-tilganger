package no.nav.fager

import junit.framework.TestCase.assertTrue
import no.nav.fager.infrastruktur.SECURE
import no.nav.fager.infrastruktur.logger
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertFalse

class SecureLogTest {
    @Test
    fun `secure log skal ikke til stdout`() {
        val log = logger()

        val stdout = captureStdout {
            log.info(SECURE, "SECRET")
        }

        assertFalse(stdout.contains("SECRET"))
    }

    @Test
    fun `vanlig log skal til stdout`() {
        val log = logger()

        val stdout = captureStdout {
            log.info("VANLIG")
        }

        assertTrue(stdout.contains("VANLIG"))
    }
}

private fun captureStdout(block: () -> Unit): String {
    val originalOut = System.out
    val captured = try {
        val byteArrayOutputStream = ByteArrayOutputStream()
        val printStream = PrintStream(byteArrayOutputStream, true)
        System.setOut(printStream)
        block()
        byteArrayOutputStream.toString()
    } finally {
        System.setOut(originalOut)
    }
    print(captured)
    return captured
}
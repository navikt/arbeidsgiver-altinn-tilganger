package no.nav.fager

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.Configurator
import ch.qos.logback.classic.spi.Configurator.ExecutionStatus
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.Appender
import ch.qos.logback.core.AppenderBase
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
import ch.qos.logback.core.spi.ContextAware
import ch.qos.logback.core.spi.ContextAwareBase
import ch.qos.logback.core.spi.LifeCycle
import net.logstash.logback.encoder.LogstashEncoder
import org.slf4j.LoggerFactory

inline fun <reified T> T.logger(): org.slf4j.Logger = LoggerFactory.getLogger(T::class.qualifiedName)

/* used by resources/META-INF/services/ch.qos.logback.classic.spi */
class LogConfig : ContextAwareBase(), Configurator {
    override fun configure(lc: LoggerContext): ExecutionStatus {
        val naisCluster = System.getenv("NAIS_CLUSTER_NAME")

        val rootAppender = MaskingAppender().setup(lc) {
            appender = ConsoleAppender<ILoggingEvent>().setup(lc) {
                if (naisCluster != null) {
                    encoder = LogstashEncoder().setup(lc)
                } else {
                    encoder = LayoutWrappingEncoder<ILoggingEvent>().setup(lc) {
                        layout = PatternLayout().setup(lc) {
                            pattern = "%d %-5level [%thread] %logger: %msg %mdc%n"
                        }
                    }
                }
            }
        }

        if (naisCluster == null || naisCluster == "dev-gcp") {
            lc.getLogger("io.ktor.auth.jwt").level = Level.TRACE
        }

        lc.getLogger(Logger.ROOT_LOGGER_NAME).apply {
            level = Level.DEBUG
            addAppender(rootAppender)
        }

        return ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY
    }
}

private fun <T> T.setup(context: LoggerContext, body: T.() -> Unit = {}): T
        where T : ContextAware,
              T : LifeCycle
{
    this.context = context
    this.body()
    this.start()
    return this
}


class MaskingAppender: AppenderBase<ILoggingEvent>() {

    var appender: Appender<ILoggingEvent>? = null

    override fun append(event: ILoggingEvent) {
        appender?.doAppend(
            object : ILoggingEvent by event {
                override fun getFormattedMessage(): String? =
                    mask(event.formattedMessage)

                override fun getThrowableProxy(): IThrowableProxy? {
                    if (event.throwableProxy == null) {
                        return null
                    }
                    return object : IThrowableProxy by event.throwableProxy {
                        override fun getMessage(): String? =
                            mask(event.throwableProxy.message)
                    }
                }

            }
        )
    }

    companion object {
        private val FNR = Regex("""(^|\D)\d{11}(?=$|\D)""")
        fun mask(string: String?): String? {
            return string?.replace(FNR, "$1***********")
        }
    }
}
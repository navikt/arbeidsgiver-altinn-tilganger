package no.nav.fager.infrastruktur

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.boolex.OnMarkerEvaluator
import ch.qos.logback.classic.spi.Configurator
import ch.qos.logback.classic.spi.Configurator.ExecutionStatus
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.Appender
import ch.qos.logback.core.AppenderBase
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
import ch.qos.logback.core.filter.EvaluatorFilter
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy
import ch.qos.logback.core.spi.ContextAware
import ch.qos.logback.core.spi.ContextAwareBase
import ch.qos.logback.core.spi.FilterReply
import ch.qos.logback.core.spi.LifeCycle
import ch.qos.logback.core.util.FileSize
import net.logstash.logback.encoder.LogstashEncoder
import org.slf4j.LoggerFactory
import org.slf4j.MarkerFactory
import java.io.File

inline fun <reified T> T.logger(): org.slf4j.Logger = LoggerFactory.getLogger(T::class.qualifiedName)

private const val SECURE_LOG_MARKER = "SECURE_LOGGER"
val SECURE: org.slf4j.Marker = MarkerFactory.getMarker(SECURE_LOG_MARKER)

/* used by resources/META-INF/services/ch.qos.logback.classic.spi */
class LogConfig : ContextAwareBase(), Configurator {
    override fun configure(lc: LoggerContext): ExecutionStatus {
        val naisCluster = System.getenv("NAIS_CLUSTER_NAME")

        val rootAppender = MaskingAppender().setup(lc) {
            addFilter(EvaluatorFilter<ILoggingEvent>().setup(lc) {
                evaluator = OnMarkerEvaluator().setup(lc) {
                    addMarker(SECURE_LOG_MARKER)
                }
                onMismatch = FilterReply.NEUTRAL
                onMatch = FilterReply.DENY
            })
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

        val secureLogFile = if (naisCluster == null)
            File.createTempFile("secure-", ".log")
                .apply { deleteOnExit() }
                .absolutePath
        else
            "/secure-logs/secure.log"

        val secureAppender = RollingFileAppender<ILoggingEvent>().setup(lc) {
            val secureAppender = this
            file = secureLogFile

            rollingPolicy = FixedWindowRollingPolicy().setup(lc) {
                setParent(secureAppender)
                fileNamePattern = "$secureLogFile.%i"
                maxIndex = 1
                minIndex = 1
            }

            triggeringPolicy = SizeBasedTriggeringPolicy<ILoggingEvent>().setup(lc) {
                maxFileSize = FileSize.valueOf("128MB")
            }
            addFilter(EvaluatorFilter<ILoggingEvent>().setup(lc) {
                evaluator = OnMarkerEvaluator().setup(lc) {
                    addMarker(SECURE_LOG_MARKER)
                }
                onMismatch = FilterReply.DENY
                onMatch = FilterReply.NEUTRAL
            })
            encoder = LogstashEncoder().setup(lc)
        }

        lc.getLogger(Logger.ROOT_LOGGER_NAME).apply {
            level = Level.TRACE
            addAppender(rootAppender)
            addAppender(secureAppender)
        }

        return ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY
    }
}

private fun <T> T.setup(context: LoggerContext, body: T.() -> Unit = {}): T
        where T : ContextAware,
              T : LifeCycle {
    this.context = context
    this.body()
    this.start()
    return this
}


class MaskingAppender : AppenderBase<ILoggingEvent>() {
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
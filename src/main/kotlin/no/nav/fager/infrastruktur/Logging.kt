package no.nav.fager.infrastruktur

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.Configurator
import ch.qos.logback.classic.spi.Configurator.ExecutionStatus
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.Appender
import ch.qos.logback.core.AppenderBase
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.ContextAware
import ch.qos.logback.core.spi.ContextAwareBase
import ch.qos.logback.core.spi.FilterReply
import ch.qos.logback.core.spi.LifeCycle
import net.logstash.logback.appender.LogstashTcpSocketAppender
import net.logstash.logback.encoder.LogstashEncoder
import no.nav.fager.infrastruktur.NaisEnvironment.clusterName
import org.slf4j.Logger
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import org.slf4j.MarkerFactory
import org.slf4j.spi.LoggingEventBuilder
import java.time.Instant

const val TEAM_LOGS = "TEAM_LOGS"
val TEAM_LOG_MARKER: Marker = MarkerFactory.getMarker(TEAM_LOGS)

/* used by resources/META-INF/services/ch.qos.logback.classic.spi */
class LogConfig : ContextAwareBase(), Configurator {

    override fun configure(lc: LoggerContext): ExecutionStatus {
        val rootAppender = MaskingAppender().setup(lc) {
            appender = ConsoleAppender<ILoggingEvent>().setup(lc) {
                encoder = LogstashEncoder().setup(lc) {
                    isIncludeMdc = true
                }
                addFilter(object : Filter<ILoggingEvent>() {
                    override fun decide(event: ILoggingEvent) = when {
                        (event.markerList ?: emptyList()).contains(TEAM_LOG_MARKER) -> FilterReply.DENY
                        else -> FilterReply.NEUTRAL
                    }
                })
            }
        }

        lc.getLogger(ROOT_LOGGER_NAME).apply {
            level = basedOnEnv(
                prod = { Level.INFO },
                dev = { Level.INFO },
                other = { Level.INFO }
            )
            addAppender(rootAppender)

            if (clusterName.isNotEmpty()) {
                addAppender(LogstashTcpSocketAppender().setup(lc) {
                    this.name = "TEAMLOGS"
                    addDestination("team-logs.nais-system:5170")
                    this.encoder = LogstashEncoder().setup(lc) {
                        this.customFields = """{
                        |"google_cloud_project":"${System.getenv("GOOGLE_CLOUD_PROJECT")}",
                        |"nais_namespace_name":"${System.getenv("NAIS_NAMESPACE")}",
                        |"nais_pod_name":"${System.getenv("NAIS_POD_NAME")}",
                        |"nais_container_name":"${System.getenv("NAIS_APP_NAME")}"
                        |}""".trimMargin()
                    }
                })
            }
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

                @Deprecated("Deprecated in Java")
                override fun getMarker(): Marker? {
                    return super.getMarker()
                }

                override fun getInstant(): Instant? {
                    return super.getInstant()
                }

            }
        )
    }

    companion object {
        val FNR = Regex("""(^|\D)\d{11}(?=$|\D)""")
        val ORGNR = Regex("""(^|\D)\d{9}(?=$|\D)""")
        val EPOST = Regex("""[\w.%+-]+@[\w.%+-]+\.[a-zA-Z]{2,}""")
        val PASSWORD = Regex("""password=.*(?=$)""")

        fun mask(string: String?): String? {
            return string?.let {
                FNR.replace(it, "$1***********")
                    .replace(ORGNR, "$1*********")
                    .replace(EPOST, "********")
                    .replace(PASSWORD, "password=********")
            }
        }
    }
}

inline fun <reified T> T.logger(): Logger = LoggerFactory.getLogger(T::class.qualifiedName)
inline fun <reified T> T.teamLogger(): Logger = MarkerLogger(logger(), TEAM_LOG_MARKER)

/**
 * Logger wrapper that enforces usage of a specific Marker for all logging methods.
 * Prevents direct use of Marker arguments in logging methods.
 *
 * Useful to ensure TeamLog marker is guaranteed when using teamLogger().
 */
class MarkerLogger(
    val logger: Logger,
    val marker: Marker
) : Logger {


    /**
     * proxy logging methods with marker
     */

    override fun trace(msg: String?) = logger.trace(marker, msg)
    override fun trace(format: String?, arg: Any?) = logger.trace(marker, format, arg)
    override fun trace(format: String?, arg1: Any?, arg2: Any?) = logger.trace(marker, format, arg1, arg2)
    override fun trace(format: String?, vararg arguments: Any?) = logger.trace(marker, format, *arguments)
    override fun trace(msg: String?, t: Throwable?) = logger.trace(marker, msg, t)
    override fun debug(msg: String?) = logger.debug(marker, msg)
    override fun debug(format: String?, arg: Any?) = logger.debug(marker, format, arg)
    override fun debug(format: String?, arg1: Any?, arg2: Any?) = logger.debug(marker, format, arg1, arg2)
    override fun debug(format: String?, vararg arguments: Any?) = logger.debug(marker, format, *arguments)
    override fun debug(msg: String?, t: Throwable?) = logger.debug(marker, msg, t)
    override fun info(msg: String?) = logger.info(marker, msg)
    override fun info(format: String?, arg: Any?) = logger.info(marker, format, arg)
    override fun info(format: String?, arg1: Any?, arg2: Any?) = logger.info(marker, format, arg1, arg2)
    override fun info(format: String?, vararg arguments: Any?) = logger.info(marker, format, *arguments)
    override fun info(msg: String?, t: Throwable?) = logger.info(marker, msg, t)
    override fun warn(msg: String?) = logger.warn(marker, msg)
    override fun warn(format: String?, arg: Any?) = logger.warn(marker, format, arg)
    override fun warn(format: String?, vararg arguments: Any?) = logger.warn(marker, format, *arguments)
    override fun warn(format: String?, arg1: Any?, arg2: Any?) = logger.warn(marker, format, arg1, arg2)
    override fun warn(msg: String?, t: Throwable?) = logger.warn(marker, msg, t)
    override fun error(msg: String?) = logger.error(marker, msg)
    override fun error(format: String?, arg: Any?) = logger.error(marker, format, arg)
    override fun error(format: String?, arg1: Any?, arg2: Any?) = logger.error(marker, format, arg1, arg2)
    override fun error(format: String?, vararg arguments: Any?) = logger.error(marker, format, *arguments)
    override fun error(msg: String?, t: Throwable?) = logger.error(marker, msg, t)


    /**
     * prevent direct marker usage
     */

    override fun isTraceEnabled(marker: Marker?): Boolean = directMarkerUsageNotAllowed()
    override fun trace(marker: Marker?, msg: String?) = directMarkerUsageNotAllowed()
    override fun trace(marker: Marker?, format: String?, arg: Any?) = directMarkerUsageNotAllowed()
    override fun trace(marker: Marker?, format: String?, arg1: Any?, arg2: Any?) = directMarkerUsageNotAllowed()
    override fun trace(marker: Marker?, format: String?, vararg argArray: Any?) = directMarkerUsageNotAllowed()
    override fun trace(marker: Marker?, msg: String?, t: Throwable?) = directMarkerUsageNotAllowed()
    override fun isDebugEnabled(marker: Marker?): Boolean = directMarkerUsageNotAllowed()
    override fun debug(marker: Marker?, msg: String?) = directMarkerUsageNotAllowed()
    override fun debug(marker: Marker?, format: String?, arg: Any?) = directMarkerUsageNotAllowed()
    override fun debug(marker: Marker?, format: String?, arg1: Any?, arg2: Any?) = directMarkerUsageNotAllowed()
    override fun debug(marker: Marker?, format: String?, vararg arguments: Any?) = directMarkerUsageNotAllowed()
    override fun debug(marker: Marker?, msg: String?, t: Throwable?) = directMarkerUsageNotAllowed()
    override fun isInfoEnabled(marker: Marker?) = directMarkerUsageNotAllowed()
    override fun info(marker: Marker?, msg: String?) = directMarkerUsageNotAllowed()
    override fun info(marker: Marker?, format: String?, arg: Any?) = directMarkerUsageNotAllowed()
    override fun info(marker: Marker?, format: String?, arg1: Any?, arg2: Any?) = directMarkerUsageNotAllowed()
    override fun info(marker: Marker?, format: String?, vararg arguments: Any?) = directMarkerUsageNotAllowed()
    override fun info(marker: Marker?, msg: String?, t: Throwable?) = directMarkerUsageNotAllowed()
    override fun isWarnEnabled(marker: Marker?): Boolean = directMarkerUsageNotAllowed()
    override fun warn(marker: Marker?, msg: String?) = directMarkerUsageNotAllowed()
    override fun warn(marker: Marker?, format: String?, arg: Any?) = directMarkerUsageNotAllowed()
    override fun warn(marker: Marker?, format: String?, arg1: Any?, arg2: Any?) = directMarkerUsageNotAllowed()
    override fun warn(marker: Marker?, format: String?, vararg arguments: Any?) = directMarkerUsageNotAllowed()
    override fun warn(marker: Marker?, msg: String?, t: Throwable?) = directMarkerUsageNotAllowed()
    override fun isErrorEnabled(marker: Marker?): Boolean = directMarkerUsageNotAllowed()
    override fun error(marker: Marker?, msg: String?) = directMarkerUsageNotAllowed()
    override fun error(marker: Marker?, format: String?, arg: Any?) = directMarkerUsageNotAllowed()
    override fun error(marker: Marker?, format: String?, arg1: Any?, arg2: Any?) = directMarkerUsageNotAllowed()
    override fun error(marker: Marker?, format: String?, vararg arguments: Any?) = directMarkerUsageNotAllowed()
    override fun error(marker: Marker?, msg: String?, t: Throwable?) = directMarkerUsageNotAllowed()
    private fun directMarkerUsageNotAllowed(): Nothing =
        throw UnsupportedOperationException("Direct use of Marker arg in MarkerLogger is not allowed")


    /**
     * override default methods, not overriden by delegation "by logger"
     */

    override fun makeLoggingEventBuilder(level: org.slf4j.event.Level?): LoggingEventBuilder? =
        logger.makeLoggingEventBuilder(level)

    override fun atLevel(level: org.slf4j.event.Level?): LoggingEventBuilder? = logger.atLevel(level)
    override fun atTrace(): LoggingEventBuilder? = logger.atTrace()
    override fun isEnabledForLevel(level: org.slf4j.event.Level?): Boolean = logger.isEnabledForLevel(level)
    override fun atDebug(): LoggingEventBuilder? = logger.atDebug()
    override fun atInfo(): LoggingEventBuilder? = logger.atInfo()
    override fun atWarn(): LoggingEventBuilder? = logger.atWarn()
    override fun atError(): LoggingEventBuilder? = logger.atError()

    override fun isTraceEnabled(): Boolean = logger.isTraceEnabled
    override fun isDebugEnabled(): Boolean = logger.isDebugEnabled
    override fun isInfoEnabled(): Boolean = logger.isInfoEnabled
    override fun isWarnEnabled(): Boolean = logger.isWarnEnabled
    override fun isErrorEnabled(): Boolean = logger.isErrorEnabled
    override fun getName(): String? = logger.name
}
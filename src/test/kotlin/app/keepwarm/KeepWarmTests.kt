package app.keepwarm

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.annotation.Scheduled
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeepWarmTests {
    private val context = ApplicationContextRunner().withInitializer {
        it.environment.propertySources.remove("systemEnvironment")
        it.environment.propertySources.remove("systemProperties")
    }.withUserConfiguration(KeepWarmConfiguration::class.java)

    @Test
    fun `unset or blank url creates no scheduled task`() {
        context.run { assertFalse(it.containsBean("keepWarm")) }
        context.withPropertyValues("KEEP_WARM_URL=   ").run { assertFalse(it.containsBean("keepWarm")) }
        context.withPropertyValues("KEEP_WARM_URL=https://example.invalid/health").run {
            assertTrue(it.containsBean("keepWarm"))
        }
    }

    @Test
    fun `each attempt uses the exact url and logs one safe result`() {
        val url = "https://example.invalid/path?secret=private"
        val seen = mutableListOf<String>()
        val results = mutableListOf<() -> Int>(
            { 204 }, { 503 }, { throw java.net.http.HttpTimeoutException(url) }, { throw IllegalStateException(url) },
        )
        val task = KeepWarm(url) { target ->
            seen.add(target)
            results.removeAt(0)()
        }
        val logger = LoggerFactory.getLogger(KeepWarm::class.java) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            repeat(4) { task.tick() }
            assertEquals(listOf(url, url, url, url), seen)
            assertEquals(listOf("Keep-warm request succeeded", "Keep-warm request failed", "Keep-warm request failed", "Keep-warm request failed"),
                logs.list.map { it.formattedMessage })
            assertTrue(logs.list.all { it.throwableProxy == null })
        } finally {
            logger.detachAppender(logs)
            logs.stop()
        }
    }

    @Test
    fun `schedule waits ten minutes and repeats every ten minutes`() {
        val schedule = KeepWarm::class.java.getMethod("tick").getAnnotation(Scheduled::class.java)
        assertEquals(600_000L, schedule.initialDelay)
        assertEquals(600_000L, schedule.fixedRate)
    }
}

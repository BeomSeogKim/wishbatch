package app.startup

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.sql.SQLException
import java.util.Properties
import javax.net.ssl.SSLHandshakeException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TemporaryDatabaseStartupTests {
    @Test
    fun `healthy PostgreSQL retains the configured pool and effective connection properties`() {
        val startup = TemporaryDatabaseStartup()
        configured().use { original ->
            original.maximumPoolSize = 7
            original.minimumIdle = 2
            original.connectionTimeout = 12_345
            original.validationTimeout = 3_456
            original.isAutoCommit = false
            original.schema = "configured_schema"
            original.connectionInitSql = "set application_name = 'configured'"
            original.addDataSourceProperty("user", "property-user")
            original.addDataSourceProperty("password", "property-password")
            original.addDataSourceProperty("sslmode", "verify-full")
            original.addDataSourceProperty("ApplicationName", "wishbatch-test")
            original.addDataSourceProperty("loginTimeout", "90")
            val originalUrl = original.jdbcUrl
            val originalProperties = Properties().apply { putAll(original.dataSourceProperties) }
            var probes = 0
            val selected = startup.select(original) { url, properties ->
                probes++
                assertEquals("jdbc:postgresql://localhost:5432/example?ssl=true&applicationName=demo&connectTimeout=3&socketTimeout=3&loginTimeout=5", url)
                assertEquals("configured-user", properties.getProperty("user"))
                assertEquals("synthetic-password", properties.getProperty("password"))
                assertEquals("verify-full", properties.getProperty("sslmode"))
                assertEquals("wishbatch-test", properties.getProperty("ApplicationName"))
                assertEquals("90", properties.getProperty("loginTimeout"))
                properties.setProperty("sslmode", "mutated-probe-copy")
            }
            assertSame(original, selected)
            assertEquals(1, probes)
            assertEquals(originalUrl, selected.jdbcUrl)
            assertEquals(originalProperties, selected.dataSourceProperties)
            assertEquals("configured-user", selected.username)
            assertEquals("synthetic-password", selected.password)
            assertEquals(7, selected.maximumPoolSize)
            assertEquals(2, selected.minimumIdle)
            assertEquals(12_345L, selected.connectionTimeout)
            assertEquals(3_456L, selected.validationTimeout)
            assertFalse(selected.isAutoCommit)
            assertEquals("configured_schema", selected.schema)
            assertEquals("set application_name = 'configured'", selected.connectionInitSql)
            assertFalse(selected.isClosed)
            assertFalse(startup.temporary)
        }
    }

    @Test
    fun `driver properties supply credentials when the pool has no explicit credentials`() {
        val startup = TemporaryDatabaseStartup()
        configured().use { original ->
            original.username = null
            original.password = null
            original.addDataSourceProperty("user", "property-user")
            original.addDataSourceProperty("password", "property-password")
            assertSame(original, startup.select(original) { _, properties ->
                assertEquals("property-user", properties.getProperty("user"))
                assertEquals("property-password", properties.getProperty("password"))
            })
        }
    }

    @Test
    fun `default H2 other JDBC and non datasource beans bypass the probe`() {
        val startup = TemporaryDatabaseStartup()
        for (url in listOf(null, "jdbc:h2:mem:app;DB_CLOSE_DELAY=-1", "jdbc:mysql://localhost/example")) {
            HikariDataSource().use { original ->
                original.jdbcUrl = url
                assertSame(original, startup.select(original) { _, _ -> error("Unexpected connection probe") })
                assertFalse(original.isClosed)
            }
        }
        val unrelated = Any()
        assertSame(unrelated, startup.postProcessAfterInitialization(unrelated, "unrelated"))
        assertFalse(startup.temporary)
    }

    @Test
    fun `only transport unreachability selects isolated temporary H2 without PostgreSQL settings`() {
        val urls = mutableSetOf<String>()
        for (cause in listOf(ConnectException("refused"), NoRouteToHostException("no route"),
            UnknownHostException("unknown"), SocketTimeoutException("timed out"))) {
            for (state in listOf("08001", "08006")) {
                val startup = TemporaryDatabaseStartup()
                val time = TestTime()
                configured().use { original ->
                    original.schema = "postgres_only"
                    original.connectionInitSql = "set application_name = 'postgres_only'"
                    original.addDataSourceProperty("sslmode", "verify-full")
                    var probes = 0
                    startup.select(original, { time.now }, time::sleep) { _, _ ->
                        probes++
                        throw SQLException("synthetic connection failure", state, cause)
                    }.use { selected ->
                        assertEquals(5, probes)
                        assertEquals(24000L, time.now)
                        assertTrue(startup.temporary)
                        assertFalse(original.isClosed)
                        assertSame(original, selected)
                        assertTrue(selected.jdbcUrl.startsWith("jdbc:h2:mem:temporary-"))
                        assertTrue(urls.add(selected.jdbcUrl), "Every fallback must get its own database")
                        assertEquals("org.h2.Driver", selected.driverClassName)
                        assertEquals("sa", selected.username)
                        assertEquals("", selected.password)
                        assertNull(selected.schema)
                        assertNull(selected.connectionInitSql)
                        assertTrue(selected.dataSourceProperties.isEmpty())
                    }
                }
            }
        }
    }

    @Test
    fun `a running PostgreSQL pool remains unchanged without another probe`() {
        val startup = TemporaryDatabaseStartup()
        val running = mock(HikariDataSource::class.java)
        `when`(running.jdbcUrl).thenReturn("jdbc:postgresql://localhost/example")
        `when`(running.isRunning).thenReturn(true)
        val selected =
            startup.select(running) { _, _ -> error("A running pool must not be probed") }
        assertSame(running, selected)
        assertFalse(startup.temporary)
    }

    @Test
    fun `authentication schema TLS ambiguous and programming failures cannot enable temporary data or leak settings`() {
        val sensitive = "jdbc:postgresql://localhost/example?password=synthetic-private-value"
        val tlsTimeout = SSLHandshakeException(sensitive).apply { initCause(SocketTimeoutException(sensitive)) }
        val failures = listOf(
            SQLException(sensitive, "28P01", ConnectException(sensitive)),
            SQLException(sensitive, "28000"),
            SQLException(sensitive, "3D000"),
            SQLException(sensitive, "42P01"),
            SQLException(sensitive, "08001"),
            SQLException(sensitive, "08006", SSLHandshakeException(sensitive)),
            SQLException(sensitive, "08006", tlsTimeout),
            SQLException(sensitive, "08001", IllegalArgumentException(sensitive)),
            IllegalArgumentException(sensitive),
            IllegalStateException(sensitive),
        )
        for (failure in failures) {
            val startup = TemporaryDatabaseStartup()
            val time = TestTime()
            var attempts = 0
            configured().use { original ->
                val thrown = assertFailsWith<IllegalStateException> {
                    startup.select(original, { time.now }, time::sleep) { _, _ ->
                        attempts++
                        throw failure
                    }
                }
                assertEquals(1, attempts)
                assertEquals(0L, time.now)
                assertTrue(time.sleeps.isEmpty())
                assertEquals("Configured database startup connection failed; temporary data was not enabled.", thrown.message)
                assertNull(thrown.cause)
                assertFalse(thrown.stackTraceToString().contains("synthetic-private-value"))
                assertFalse(thrown.stackTraceToString().contains("jdbc:postgresql:"))
                assertFalse(startup.temporary)
                assertFalse(original.isClosed)
            }
        }
    }

    @Test
    fun `probe URL changes only timeout parameters including duplicate unbounded values`() {
        val startup = TemporaryDatabaseStartup()
        assertEquals("jdbc:postgresql://one,two/example?sslmode=verify-full&options=-c%20search_path%3Dpublic&connectTimeout=3&socketTimeout=3&loginTimeout=5",
            startup.probeUrl("jdbc:postgresql://one,two/example?connectTimeout=0&sslmode=verify-full&socketTimeout=90&loginTimeout=0&connectTimeout=99&options=-c%20search_path%3Dpublic"))
        assertEquals("jdbc:postgresql:example?connectTimeout=3&socketTimeout=3&loginTimeout=5",
            startup.probeUrl("jdbc:postgresql:example"))
    }

    @Test
    fun `transient failures recover on the scheduled attempt without changing the configured pool`() {
        for (successAttempt in 1..5) {
            val startup = TemporaryDatabaseStartup()
            val time = TestTime()
            val starts = mutableListOf<Long>()
            configured().use { original ->
                val url = original.jdbcUrl
                original.maximumPoolSize = 7
                val selected = startup.select(original, { time.now }, time::sleep) { _, _ ->
                    starts.add(time.now)
                    if (starts.size < successAttempt) throw unreachable()
                }
                assertSame(original, selected)
                assertEquals(url, selected.jdbcUrl)
                assertEquals(7, selected.maximumPoolSize)
                assertFalse(startup.temporary)
                assertEquals((0 until successAttempt).map { it * 6000L }, starts)
                assertEquals((successAttempt - 1) * 6000L, time.now)
            }
        }
    }

    @Test
    fun `five immediate or slow failures preserve scheduled starts and bounded exhaustion`() {
        for (duration in listOf(0L, 5000L)) {
            val startup = TemporaryDatabaseStartup()
            val time = TestTime()
            val starts = mutableListOf<Long>()
            configured().use { original ->
                startup.select(original, { time.now }, time::sleep) { _, _ ->
                    starts.add(time.now)
                    time.now += duration
                    throw unreachable()
                }
                assertEquals(listOf(0L, 6000L, 12000L, 18000L, 24000L), starts)
                assertEquals(List(4) { 6000L - duration }, time.sleeps)
                assertEquals(24000L + duration, time.now)
                assertTrue(startup.temporary)
            }
        }
    }

    @Test
    fun `nonretryable failure after a transient failure stops immediately`() {
        val startup = TemporaryDatabaseStartup()
        val time = TestTime()
        var attempts = 0
        configured().use { original ->
            assertFailsWith<IllegalStateException> {
                startup.select(original, { time.now }, time::sleep) { _, _ ->
                    attempts++
                    if (attempts == 1) throw unreachable()
                    throw SQLException("Authentication rejected", "28P01")
                }
            }
            assertEquals(2, attempts)
            assertEquals(6000L, time.now)
            assertFalse(startup.temporary)
        }
    }

    @Test
    fun `interruption before a probe during waiting or in the driver stops without fallback`() {
        for (phase in listOf("before", "wait", "driver", "direct")) {
            val startup = TemporaryDatabaseStartup()
            var attempts = 0
            configured().use { original ->
                try {
                    if (phase == "before") Thread.currentThread().interrupt()
                    assertFailsWith<InterruptedException> {
                        startup.select(original, { 0L }, { throw InterruptedException("Wait interrupted") }) { _, _ ->
                            attempts++
                            when (phase) {
                                "driver" -> {
                                    Thread.currentThread().interrupt()
                                    throw IllegalStateException("Driver interrupted")
                                }
                                "direct" -> throw InterruptedException("Probe interrupted")
                                else -> throw unreachable()
                            }
                        }
                    }
                    assertTrue(Thread.currentThread().isInterrupted)
                    assertEquals(if (phase == "before") 0 else 1, attempts)
                    assertFalse(startup.temporary)
                } finally {
                    Thread.interrupted()
                }
            }
        }
    }

    private class TestTime {
        var now = 0L
        val sleeps = mutableListOf<Long>()

        fun sleep(duration: Long) {
            sleeps.add(duration)
            now += duration
        }
    }

    private fun unreachable() = SQLException("Connection refused", "08001", ConnectException())

    private fun configured() = HikariDataSource().apply {
        jdbcUrl = "jdbc:postgresql://localhost:5432/example?ssl=true&connectTimeout=90&applicationName=demo&socketTimeout=0&loginTimeout=0"
        username = "configured-user"
        password = "synthetic-password"
    }
}

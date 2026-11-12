package app.startup

import app.Application
import app.campaign.workspace.WorkspaceService
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TemporaryDatabaseBootTests {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `normal H2 keeps configuration and has no temporary notice`() {
        val url = "jdbc:h2:mem:normal-${UUID.randomUUID()};DB_CLOSE_DELAY=0"
        application(url, "sa").use { context ->
            assertEquals(url, context.getBean(HikariDataSource::class.java).jdbcUrl)
            assertFalse(context.getBean(TemporaryDatabaseStartup::class.java).temporary)
            assertFalse(page(context, "/").contains("Temporary demo data:"))
            seededDemo(context)
        }
    }

    @Test
    fun `unreachable PostgreSQL boots isolated usable temporary demo and closes pool`() {
        val port = ServerSocket(0).use { it.localPort }
        val started = System.nanoTime()
        val context = application("jdbc:postgresql://127.0.0.1:$port/unreachable", "unavailable")
        val source = context.getBean(HikariDataSource::class.java)
        context.use {
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(elapsed in 24000..40000, "Startup took $elapsed milliseconds including application initialization")
            println("Temporary database startup elapsed: $elapsed ms")
            assertTrue(context.getBean(TemporaryDatabaseStartup::class.java).temporary)
            assertTrue(source.jdbcUrl.startsWith("jdbc:h2:mem:temporary-"))
            assertContains(page(context, "/"), "Temporary demo data:")
            assertContains(page(context, "/"), "Changes here are lost on restart")
            assertContains(seededDemo(context), "Temporary demo data:")
        }
        assertTrue(source.isClosed)
    }

    @Test
    fun `actual PostgreSQL total login timeout is bounded and selects temporary data`() {
        ServerSocket(0).use { server ->
            server.soTimeout = 10000
            val sockets = Collections.synchronizedList(mutableListOf<Socket>())
            val listener = Thread.ofPlatform().daemon().start {
                val socket = server.accept().also { sockets.add(it) }
                try {
                    // Partial protocol bytes keep each read below the socket timeout,
                    // while the complete handshake exceeds the total login timeout.
                    val output = socket.getOutputStream()
                    output.write('R'.code)
                    repeat(4) {
                        output.write(0)
                        output.flush()
                        Thread.sleep(2000)
                    }
                } catch (_: IOException) {
                    // The driver closes its abandoned connection after the timeout.
                }
            }
            val source = HikariDataSource().apply {
                jdbcUrl = "jdbc:postgresql://127.0.0.1:${server.localPort},127.0.0.1:${server.localPort}/unavailable?sslmode=disable"
                username = "unavailable"
                password = ""
            }
            try {
                val startup = TemporaryDatabaseStartup()
                val started = System.nanoTime()
                val failure = assertFailsWith<SQLException> {
                    DriverManager.getConnection(startup.probeUrl(source.jdbcUrl), "unavailable", "").use { }
                }
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                assertTrue(elapsed in 4000..8000, "Probe took $elapsed milliseconds")
                assertEquals("08001", failure.sqlState)
                assertEquals(null, failure.cause)
                assertEquals("org.postgresql.util.PSQLException", failure.javaClass.name)
                assertTrue(failure.stackTrace.any {
                    it.className == "org.postgresql.Driver\$ConnectTask" && it.methodName == "getResult"
                })
                var virtualNow = 0L
                var attempts = 0
                startup.select(source, { virtualNow }, { virtualNow += it }) { _, _ ->
                    attempts++
                    throw failure
                }
                assertEquals(5, attempts)
                assertEquals(24000L, virtualNow)
                assertTrue(startup.temporary)
                assertTrue(source.jdbcUrl.startsWith("jdbc:h2:mem:temporary-"))
            } finally {
                source.close()
                sockets.forEach { it.close() }
                listener.join(6000)
            }
        }
    }

    @Test
    fun `reachable disposable PostgreSQL retains data across restart and rejects auth and schema failures`() {
        val initdb = executable("initdb")
        val pgctl = executable("pg_ctl")
        assumeTrue(initdb != null && pgctl != null, "Optional PostgreSQL binaries are not installed")
        assumeTrue(System.getProperty("user.name") != "root", "PostgreSQL cannot initialize a cluster as root")
        val data = directory.resolve("data")
        run(initdb!!, "-D", data.toString(), "-U", "postgres", "--auth=trust", "--no-locale", "--encoding=UTF8")
        val port = ServerSocket(0).use { it.localPort }
        Files.writeString(data.resolve("postgresql.conf"), "\nlisten_addresses = '127.0.0.1'\nport = $port\nunix_socket_directories = ''\n", StandardOpenOption.APPEND)
        val url = "jdbc:postgresql://127.0.0.1:$port/postgres"
        withPostgresServer(pgctl!!, data) {
            val token = application(url, "postgres").use { context ->
                val source = context.getBean(HikariDataSource::class.java)
                assertEquals(url, source.jdbcUrl)
                assertEquals(4, source.maximumPoolSize)
                assertEquals("public", source.schema)
                assertFalse(context.getBean(TemporaryDatabaseStartup::class.java).temporary)
                assertFalse(page(context, "/").contains("Temporary demo data:"))
                context.getBean(WorkspaceService::class.java).create().token
            }
            application(url, "postgres").use { context ->
                val workspaces = context.getBean(WorkspaceService::class.java)
                assertNotNull(workspaces.findByToken(token))
                assertTrue(workspaces.listWishes(token).isNotEmpty())
                assertFalse(page(context, "/w/$token/maker").contains("Temporary demo data:"))
            }
            val authentication = assertFailsWith<Exception> { application(url, "missing_role").close() }
            assertTrue(generateSequence(authentication as Throwable) { it.cause }.any {
                it.message == "Configured database startup connection failed; temporary data was not enabled."
            })
            DriverManager.getConnection(url, "postgres", "").use { connection ->
                connection.createStatement().use { it.execute("create database invalid_schema") }
            }
            val invalidUrl = "jdbc:postgresql://127.0.0.1:$port/invalid_schema"
            DriverManager.getConnection(invalidUrl, "postgres", "").use { connection ->
                connection.createStatement().use { it.execute("create table pledges (id uuid)") }
            }
            val schema = assertFailsWith<Exception> { application(invalidUrl, "postgres").close() }
            assertTrue(generateSequence(schema as Throwable) { it.cause }.any {
                it.message?.contains("Unsupported schema: pledges.state is missing") == true
            })
        }
    }

    @Test
    fun `failed fixture start keeps startup diagnostics and does not stop an absent server`() {
        Files.writeString(directory.resolve("server.log"), "Synthetic server startup diagnostic")
        val failure = assertFailsWith<AssertionError> {
            withPostgresServer(fakePgCtl(1, 3, 1), directory.resolve("data")) {
                error("Application must not run after failed server startup")
            }
        }
        assertContains(failure.message.orEmpty(), "fixture start")
        assertContains(failure.message.orEmpty(), "Synthetic server startup diagnostic")
        assertEquals("start\nstatus\n", Files.readString(directory.resolve("actions.log")))
        assertTrue(failure.suppressed.isEmpty())
        Files.list(directory).use { files ->
            val logs = files.filter { it.fileName.toString().startsWith("command-") }.toList()
            assertEquals(2, logs.size)
            assertTrue(logs.any { Files.readString(it).contains("fixture start") })
            assertTrue(logs.any { Files.readString(it).contains("fixture status") })
        }
    }

    @Test
    fun `fixture cleanup cannot replace a genuine assertion and cleanup alone still fails`() {
        val pgctl = fakePgCtl(0, 0, 1)
        val primary = AssertionError("Original application assertion")
        val failure = assertFailsWith<AssertionError> {
            withPostgresServer(pgctl, directory.resolve("data")) { throw primary }
        }
        assertSame(primary, failure)
        assertEquals(1, failure.suppressed.size)
        assertContains(failure.suppressed.single().message.orEmpty(), "fixture stop")
        val cleanup = assertFailsWith<AssertionError> {
            withPostgresServer(pgctl, directory.resolve("data")) { }
        }
        assertContains(cleanup.message.orEmpty(), "fixture stop")
    }

    private fun withPostgresServer(pgctl: String, data: Path, block: () -> Unit) {
        var primary: Throwable? = null
        try {
            run(pgctl, "-D", data.toString(), "-l", directory.resolve("server.log").toString(), "-w", "start")
            block()
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                if (run(pgctl, "-D", data.toString(), "status", allowedExitCodes = setOf(0, 3)) == 0) {
                    run(pgctl, "-D", data.toString(), "-m", "immediate", "-w", "stop")
                }
            } catch (cleanup: Throwable) {
                if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private fun fakePgCtl(startExit: Int, statusExit: Int, stopExit: Int): String {
        val script = directory.resolve("pgctl-fixture.sh")
        Files.writeString(script, """
            #!/bin/sh
            for action in "${'$'}@"; do :; done
            echo "${'$'}action" >> "${directory.resolve("actions.log")}"
            echo "fixture ${'$'}action"
            case "${'$'}action" in
                start) exit $startExit ;;
                status) exit $statusExit ;;
                stop) exit $stopExit ;;
            esac
            exit 2
        """.trimIndent())
        check(script.toFile().setExecutable(true))
        return script.toString()
    }

    private fun seededDemo(context: ConfigurableApplicationContext): String {
        val workspaces = context.getBean(WorkspaceService::class.java)
        val workspace = workspaces.create()
        assertTrue(workspaces.listWishes(workspace.token).isNotEmpty())
        assertTrue(workspaces.listOptions(workspace.token).isNotEmpty())
        return page(context, "/w/${workspace.token}/maker")
    }

    private fun page(context: ConfigurableApplicationContext, path: String): String {
        val port = context.environment.getRequiredProperty("local.server.port")
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET().build()
        return HttpClient.newHttpClient().use { client ->
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
            response.body()
        }
    }

    private fun application(url: String, username: String) = SpringApplicationBuilder(Application::class.java).run(
        "--server.port=0", "--spring.main.banner-mode=off", "--spring.datasource.url=$url",
        "--spring.datasource.username=$username", "--spring.datasource.password=",
        "--spring.datasource.hikari.maximum-pool-size=4",
        "--spring.datasource.hikari.schema=${if (url.startsWith("jdbc:postgresql:")) "public" else "PUBLIC"}",
        "--PAYPAL_BASE_URL=https://api-m.sandbox.paypal.com", "--PAYPAL_CLIENT_ID=", "--PAYPAL_CLIENT_SECRET=",
        "--PAYPAL_WEBHOOK_ID=", "--LLM_API_KEY=", "--LLM_DAILY_RUN_CAP=0", "--LLM_SEED_RUN=false",
    )

    private fun executable(name: String): String? = System.getenv("PATH").split(File.pathSeparator)
        .map { Path.of(it, name) }.firstOrNull(Files::isExecutable)?.toString()

    private fun run(vararg command: String, allowedExitCodes: Set<Int> = setOf(0)): Int {
        val output = Files.createTempFile(directory, "command-", ".log").toFile()
        val process = ProcessBuilder(*command).redirectErrorStream(true).redirectOutput(output).start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            error("Disposable PostgreSQL command timed out\n${diagnostics(output)}")
        }
        val exit = process.exitValue()
        assertTrue(exit in allowedExitCodes, "Unexpected command exit $exit\n${diagnostics(output)}")
        return exit
    }

    private fun diagnostics(output: File): String {
        val server = directory.resolve("server.log").toFile()
        return "Command output:\n${output.readText().takeLast(2000)}\nServer output:\n" +
            if (server.exists()) server.readText().takeLast(4000) else "No server log was created."
    }
}

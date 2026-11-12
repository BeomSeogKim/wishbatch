package app.startup

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

class PostgresSchemaUpgradeTests {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `isolated PostgreSQL upgrades old checks and native enums across application restarts`() {
        val initdb = executable("initdb")
        val pgctl = executable("pg_ctl")
        assumeTrue(initdb != null && pgctl != null, "Optional PostgreSQL binaries are not installed")
        assumeTrue(System.getProperty("user.name") != "root", "PostgreSQL cannot initialize a cluster as root")
        val data = directory.resolve("data")
        run(initdb!!, "-D", data.toString(), "-U", "postgres", "--auth=trust", "--no-locale", "--encoding=UTF8")
        val port = ServerSocket(0).use { it.localPort }
        Files.writeString(data.resolve("postgresql.conf"), """

            listen_addresses = '127.0.0.1'
            port = $port
            unix_socket_directories = ''
        """.trimIndent(), java.nio.file.StandardOpenOption.APPEND)
        try {
            run(pgctl!!, "-D", data.toString(), "-l", directory.resolve("server.log").toString(), "-w", "start")
            DriverManager.getConnection("jdbc:postgresql://127.0.0.1:$port/postgres", "postgres", "").use { connection ->
                for (nativeEnum in listOf(false, true)) {
                    val database = if (nativeEnum) "native_upgrade" else "check_upgrade"
                    connection.createStatement().use { it.execute("create database $database") }
                    ExistingSchemaUpgradeTests().verifyApplicationUpgrade(
                        "jdbc:postgresql://127.0.0.1:$port/$database", nativeEnum, postgres = true,
                    )
                }
            }
        } finally {
            run(pgctl!!, "-D", data.toString(), "-m", "immediate", "-w", "stop")
        }
    }

    private fun executable(name: String): String? = System.getenv("PATH").split(File.pathSeparator)
        .map { Path.of(it, name) }.firstOrNull(Files::isExecutable)?.toString()

    private fun run(vararg command: String) {
        val output = directory.resolve("command.log").toFile()
        val process = ProcessBuilder(*command).redirectErrorStream(true).redirectOutput(output).start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            error("Disposable PostgreSQL command timed out: ${command.first()}")
        }
        assertEquals(0, process.exitValue(), output.readText().takeLast(3000))
    }
}

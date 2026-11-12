package app.startup

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.stereotype.Component
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle
import java.util.UUID
import javax.net.ssl.SSLException

@Component
class TemporaryDatabaseStartup : BeanPostProcessor {
    @Volatile
    final var temporary: Boolean = false
        private set

    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        if (bean !is HikariDataSource) return bean
        return select(bean) { url, properties ->
            DriverManager.getConnection(url, properties).use { }
        }
    }

    internal fun select(
        configured: HikariDataSource,
        connect: (String, Properties) -> Unit,
    ): HikariDataSource {
        val url = configured.jdbcUrl ?: return configured
        if (!url.startsWith("jdbc:postgresql:")) return configured
        if (configured.isRunning) return configured
        val properties = Properties().apply {
            putAll(configured.dataSourceProperties)
            configured.username?.let { setProperty("user", it) }
            configured.password?.let { setProperty("password", it) }
        }
        try {
            connect(probeUrl(url), properties)
            return configured
        } catch (failure: SQLException) {
            if (!unreachable(failure)) {
                // Driver messages may include connection settings or credentials.
                throw connectionFailure()
            }
        } catch (failure: RuntimeException) {
            throw connectionFailure()
        }
        HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:temporary-${UUID.randomUUID()};DB_CLOSE_DELAY=0"
            username = "sa"
            password = ""
            driverClassName = "org.h2.Driver"
        }.copyStateTo(configured)
        temporary = true
        return configured
    }

    private fun unreachable(failure: SQLException): Boolean {
        if (failure.sqlState !in setOf("08001", "08006")) return false
        // The driver reports total login timeout without a cause. Its dedicated
        // path and localized message distinguish it from other connection errors.
        if (failure.sqlState == "08001" && failure.cause == null &&
            failure.javaClass.name == "org.postgresql.util.PSQLException" &&
            failure.message == loginTimeoutMessage() &&
            failure.stackTrace.any { it.className == "org.postgresql.Driver\$ConnectTask" && it.methodName == "getResult" }
        ) return true
        val causes = generateSequence(failure.cause) { it.cause }.toList()
        if (causes.any { it is SSLException }) return false
        return causes.any {
            it is ConnectException || it is NoRouteToHostException ||
                it is UnknownHostException || it is SocketTimeoutException
        }
    }

    private fun connectionFailure() = IllegalStateException(
        "Configured database startup connection failed; temporary data was not enabled.",
    )

    private fun loginTimeoutMessage(): String = try {
        ResourceBundle.getBundle("org.postgresql.translation.messages", Locale.getDefault(Locale.Category.DISPLAY))
            .getString("Connection attempt timed out.")
    } catch (_: MissingResourceException) {
        "Connection attempt timed out."
    }

    internal fun probeUrl(url: String): String {
        val base = url.substringBefore('?')
        val parameters = url.substringAfter('?', "").split('&').filter {
            it.isNotEmpty() && it.substringBefore('=') !in setOf("connectTimeout", "socketTimeout", "loginTimeout")
        }
        // Driver login timeout bounds the entire probe, including multi-host URLs.
        return base + "?" + (parameters + listOf("connectTimeout=3", "socketTimeout=3", "loginTimeout=5")).joinToString("&")
    }
}

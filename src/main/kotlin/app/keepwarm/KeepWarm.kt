package app.keepwarm

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.scheduling.annotation.Scheduled
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class KeepWarmEnabled : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
        !context.environment.getProperty("KEEP_WARM_URL").isNullOrBlank()
}

@Configuration
@Conditional(KeepWarmEnabled::class)
class KeepWarmConfiguration {
    @Bean
    fun keepWarm(context: org.springframework.core.env.Environment): KeepWarm {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        return KeepWarm(context.getRequiredProperty("KEEP_WARM_URL")) { url ->
            val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build()
            client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        }
    }
}

class KeepWarm(private val url: String, private val request: (String) -> Int) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedRate = 600_000, initialDelay = 600_000)
    fun tick() {
        val success = try {
            request(url) in 200..299
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        }
        logger.info("Keep-warm request {}", if (success) "succeeded" else "failed")
    }
}

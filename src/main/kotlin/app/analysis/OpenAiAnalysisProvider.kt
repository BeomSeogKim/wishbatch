package app.analysis

import app.campaign.port.AnalysisFailure
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class AnalysisRequest(val input: String, val schema: Map<String, Any>, val retry: Boolean)

sealed interface ProviderResult {
    data class Success(val model: String, val json: String) : ProviderResult
    data class Failure(val reason: AnalysisFailure) : ProviderResult
}

fun interface AnalysisProvider {
    fun generate(request: AnalysisRequest, budget: Duration): ProviderResult
}

internal data class ProviderHttpResponse(val status: Int, val body: String)

internal fun interface AnalysisTransport {
    fun send(request: HttpRequest): CompletableFuture<ProviderHttpResponse>
}

class OpenAiAnalysisProvider internal constructor(
    private val properties: AnalysisProperties,
    private val mapper: JsonMapper,
    private val transport: AnalysisTransport,
) : AnalysisProvider {
    constructor(properties: AnalysisProperties, mapper: JsonMapper) : this(
        properties, mapper, httpTransport(),
    )

    override fun generate(request: AnalysisRequest, budget: Duration): ProviderResult {
        val started = System.nanoTime()
        if (properties.apiKey.isBlank()) return ProviderResult.Failure(AnalysisFailure.NOT_CONFIGURED)
        if (budget.isNegative || budget.isZero) return ProviderResult.Failure(AnalysisFailure.TIMEOUT)
        val body = mapper.writeValueAsString(mapOf(
            "model" to properties.model,
            "store" to false,
            "max_output_tokens" to 16000,
            "instructions" to AnalysisPrompt.instructions + if (request.retry) {
                "\nThe previous output was invalid. Return one complete schema-shaped result with at most one proposal."
            } else "",
            "input" to listOf(mapOf("role" to "user", "content" to request.input)),
            "text" to mapOf("format" to mapOf(
                "type" to "json_schema", "name" to "wish_analysis", "strict" to true, "schema" to request.schema,
            )),
        ))
        val pending = try {
            val httpRequest = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/responses"))
                .timeout(budget)
                .header("Authorization", "Bearer ${properties.apiKey}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            transport.send(httpRequest)
        } catch (_: IllegalArgumentException) {
            return ProviderResult.Failure(AnalysisFailure.PROVIDER_FAILURE)
        }
        return try {
            val remaining = budget.toNanos() - (System.nanoTime() - started)
            if (remaining <= 0) throw TimeoutException()
            val response = pending.get(remaining, TimeUnit.NANOSECONDS)
            if (response.status !in 200..299) {
                ProviderResult.Failure(if (response.status == 408 || response.status == 504) {
                    AnalysisFailure.TIMEOUT
                } else AnalysisFailure.PROVIDER_FAILURE)
            } else {
                decode(response.body)
            }
        } catch (_: TimeoutException) {
            pending.cancel(true)
            ProviderResult.Failure(AnalysisFailure.TIMEOUT)
        } catch (exception: ExecutionException) {
            ProviderResult.Failure(if (exception.cause is HttpTimeoutException) {
                AnalysisFailure.TIMEOUT
            } else AnalysisFailure.PROVIDER_FAILURE)
        } catch (_: InterruptedException) {
            pending.cancel(true)
            Thread.currentThread().interrupt()
            ProviderResult.Failure(AnalysisFailure.PROVIDER_FAILURE)
        } catch (_: CancellationException) {
            ProviderResult.Failure(AnalysisFailure.PROVIDER_FAILURE)
        }
    }

    private fun decode(body: String): ProviderResult = try {
        val response = mapper.readTree(body)
        if (response.path("status").asString() != "completed" ||
            !response.path("error").isMissingNode && !response.path("error").isNull
        ) {
            ProviderResult.Failure(AnalysisFailure.PROVIDER_FAILURE)
        } else {
            val contents = response.path("output").filter { it.path("type").asString() == "message" }
                .flatMap { it.path("content").toList() }
            val texts = contents.filter { it.path("type").asString() == "output_text" }
            val model = response.path("model").asString("")
            if (contents.any { it.path("type").asString() == "refusal" } || texts.size != 1 || model.isBlank()) {
                ProviderResult.Failure(AnalysisFailure.PROVIDER_FAILURE)
            } else {
                ProviderResult.Success(model, texts.single().path("text").asString())
            }
        }
    } catch (_: JacksonException) {
        ProviderResult.Failure(AnalysisFailure.PROVIDER_FAILURE)
    }

    companion object {
        private fun httpTransport(): AnalysisTransport {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            return AnalysisTransport { request ->
                val pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                val mapped = pending.thenApply { ProviderHttpResponse(it.statusCode(), it.body()) }
                mapped.whenComplete { _, _ -> if (mapped.isCancelled) pending.cancel(true) }
                mapped
            }
        }
    }
}

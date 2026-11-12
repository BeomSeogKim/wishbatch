package app.analysis

import app.analysis.replies.ReplyPrompt
import app.campaign.port.AnalysisFailure
import org.slf4j.LoggerFactory
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

enum class AnalysisStage { CLASSIFY, PROPOSE }
enum class AnalysisPurpose { ANALYSIS, REPLY, GLOSS, OFFER_COPY }

data class AnalysisRequest(
    val input: String,
    val schema: Map<String, Any>,
    val retry: Boolean,
    val stage: AnalysisStage = AnalysisStage.CLASSIFY,
    val maxOutputTokens: Int = 4096,
    val purpose: AnalysisPurpose = AnalysisPurpose.ANALYSIS,
)

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
    private val auxiliaryCallBudget: AuxiliaryCallBudget = sharedAuxiliaryCallBudget,
) : AnalysisProvider {
    private val logger = LoggerFactory.getLogger(javaClass)

    constructor(properties: AnalysisProperties, mapper: JsonMapper) : this(
        properties, mapper, httpTransport(),
    )

    override fun generate(request: AnalysisRequest, budget: Duration): ProviderResult {
        val started = System.nanoTime()
        if (properties.apiKey.isBlank()) return ProviderResult.Failure(AnalysisFailure.NOT_CONFIGURED)
        if (budget.isNegative || budget.isZero) return ProviderResult.Failure(AnalysisFailure.TIMEOUT)
        val auxiliaryOutputLimit = when (request.purpose) {
            AnalysisPurpose.GLOSS -> 2048
            AnalysisPurpose.OFFER_COPY -> 1536
            else -> null
        }
        if (auxiliaryOutputLimit != null && (request.retry ||
            budget > Duration.ofSeconds(15) || request.maxOutputTokens !in 1..auxiliaryOutputLimit)
        ) return ProviderResult.Failure(AnalysisFailure.INVALID_OUTPUT)
        val instructions = when (request.purpose) {
            AnalysisPurpose.GLOSS -> AuxiliaryInstructions.gloss
            AnalysisPurpose.OFFER_COPY -> AuxiliaryInstructions.offerCopy
            AnalysisPurpose.REPLY -> ReplyPrompt.instructions
            AnalysisPurpose.ANALYSIS -> AnalysisPrompt.instructions + "\n" + AnalysisStages.instructions(request.stage) + if (request.retry) {
                "\nThe previous output was invalid. Return one complete schema-shaped result with at most one proposal."
            } else ""
        }
        val body = mapper.writeValueAsString(mapOf(
            "model" to properties.model,
            "store" to false,
            "max_output_tokens" to request.maxOutputTokens,
            "reasoning" to mapOf("effort" to properties.reasoningEffort),
            "instructions" to instructions,
            "input" to listOf(mapOf("role" to "user", "content" to request.input)),
            "text" to mapOf("verbosity" to "low", "format" to mapOf(
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
            if (auxiliaryOutputLimit != null) {
                if (budget.toNanos() - (System.nanoTime() - started) <= 0) {
                    return ProviderResult.Failure(AnalysisFailure.TIMEOUT)
                }
                if (!auxiliaryCallBudget.reserve(properties.dailyRunCap)) {
                    return ProviderResult.Failure(AnalysisFailure.RUN_LIMIT_REACHED)
                }
            }
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
                decode(response.body).also {
                    logger.debug(
                        "Analysis stage={} latencyMs={} result={}", request.stage,
                        (System.nanoTime() - started) / 1000000, it.javaClass.simpleName,
                    )
                }
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
        logger.debug(
            "Analysis tokens input={} output={} reasoning={}",
            response.path("usage").path("input_tokens").asInt(0),
            response.path("usage").path("output_tokens").asInt(0),
            response.path("usage").path("output_tokens_details").path("reasoning_tokens").asInt(0),
        )
        logger.debug(
            "Analysis completed={} outputLimitReached={}",
            response.path("status").asString() == "completed",
            response.path("incomplete_details").path("reason").asString() == "max_output_tokens",
        )
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
        private val sharedAuxiliaryCallBudget = AuxiliaryCallBudget()

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

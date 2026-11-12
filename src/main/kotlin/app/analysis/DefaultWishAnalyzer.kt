package app.analysis

import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.port.AnalysisFailure
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalysisResult
import app.campaign.port.AnalysisValidationFlag
import app.campaign.port.AnalysisValidationResult
import app.campaign.port.AnalysisValidationRule
import app.campaign.port.WishAnalyzer
import app.campaign.rules.analysisInputHash
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

class DefaultWishAnalyzer(
    private val properties: AnalysisProperties,
    private val provider: AnalysisProvider,
    private val mapper: JsonMapper,
    private val clock: Clock = Clock.systemUTC(),
    private val nanoTime: () -> Long = System::nanoTime,
) : WishAnalyzer {
    private val validator = AnalysisValidator(mapper)
    private var capDate: LocalDate? = null
    private var callsToday = 0

    private val permits = Semaphore(properties.concurrency)

    override fun analyze(options: List<Option>, wishes: List<Wish>): AnalysisResult {
        val started = nanoTime()
        fun remaining() = Duration.ofNanos(Duration.ofSeconds(60).toNanos() - (nanoTime() - started))
        if (properties.apiKey.isBlank()) return AnalysisResult.Failure(AnalysisFailure.NOT_CONFIGURED)
        if (wishes.size > 200 || wishes.any { it.text.length > 500 } ||
            wishes.map { it.id }.distinct().size != wishes.size || options.map { it.id }.distinct().size != options.size
        ) return AnalysisResult.Failure(AnalysisFailure.INVALID_OUTPUT)
        if (AnalysisPrompt.input(mapper, options, wishes).length > 160000) {
            return AnalysisResult.Failure(AnalysisFailure.INVALID_OUTPUT)
        }
        val inputHash = analysisInputHash(options, wishes)
        val flags = mutableListOf<AnalysisValidationFlag>()
        val executor = Executors.newFixedThreadPool(properties.concurrency) { task ->
            Thread(task, "wish-analysis").apply { isDaemon = true }
        }
        val futures = mutableListOf<Future<StageResult>>()
        try {
            val batches = wishes.chunked(properties.batchSize)
            batches.forEach { batch ->
                futures += executor.submit<StageResult> {
                    generate(AnalysisRequest(AnalysisPrompt.input(mapper, options, batch),
                        AnalysisStages.schema(options, AnalysisStage.CLASSIFY), false), options, batch, ::remaining)
                }
            }
            val outputs = mutableListOf<AnalysisOutput>()
            val analyzedWishes = mutableListOf<Wish>()
            var batchFailure = AnalysisFailure.INVALID_OUTPUT
            for ((index, future) in futures.withIndex()) {
                val budget = remaining().toNanos()
                if (budget <= 0) return AnalysisResult.Failure(AnalysisFailure.TIMEOUT, flags)
                val result = future.get(budget, TimeUnit.NANOSECONDS)
                flags += result.flags
                if (result.output == null) {
                    batchFailure = result.failure
                    flags += batches[index].map {
                        AnalysisValidationFlag(AnalysisValidationRule.UNANALYZED_WISH,
                            "wishes[${it.id}]", "Wish could not be analyzed.")
                    }
                } else {
                    outputs += result.output
                    analyzedWishes += batches[index]
                }
            }
            if (outputs.isEmpty()) return AnalysisResult.Failure(batchFailure, flags.toList())
            val merged = AnalysisOutput(
                outputs.flatMap { it.wishes }, outputs.flatMap { it.verdicts }, null,
                "No proposal selected.", emptyList(), emptyList(), emptyList(), options.map { it.id },
            )
            val proposalInput = AnalysisStages.proposalInput(mapper, options, merged)
            if (proposalInput.length > 160000) return AnalysisResult.Failure(AnalysisFailure.INVALID_OUTPUT, flags)
            val proposalTask = executor.submit<StageResult> { generate(AnalysisRequest(
                proposalInput,
                AnalysisStages.schema(options, AnalysisStage.PROPOSE), false, AnalysisStage.PROPOSE, 2048,
            ), options, analyzedWishes, ::remaining, merged) }
            futures += proposalTask
            val proposalBudget = remaining().toNanos()
            if (proposalBudget <= 0) return AnalysisResult.Failure(AnalysisFailure.TIMEOUT, flags)
            val proposal = proposalTask.get(proposalBudget, TimeUnit.NANOSECONDS)
            flags += proposal.flags
            if (remaining().isNegative || remaining().isZero) return AnalysisResult.Failure(AnalysisFailure.TIMEOUT, flags)
            val output = proposal.output ?: return AnalysisResult.Failure(proposal.failure, flags)
            return AnalysisResult.Success(proposal.model, AnalysisValidationResult.Accepted(output, flags.toList()),
                inputHash, clock.instant())
        } catch (_: TimeoutException) {
            return AnalysisResult.Failure(AnalysisFailure.TIMEOUT, flags.toList())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return AnalysisResult.Failure(AnalysisFailure.PROVIDER_FAILURE, flags.toList())
        } catch (_: ExecutionException) {
            return AnalysisResult.Failure(AnalysisFailure.PROVIDER_FAILURE, flags.toList())
        } finally {
            futures.forEach { it.cancel(true) }
            executor.shutdownNow()
        }
    }

    private data class StageResult(
        val output: AnalysisOutput? = null,
        val model: String = "",
        val failure: AnalysisFailure = AnalysisFailure.INVALID_OUTPUT,
        val flags: List<AnalysisValidationFlag> = emptyList(),
    )

    private fun generate(
        request: AnalysisRequest,
        options: List<Option>,
        wishes: List<Wish>,
        remaining: () -> Duration,
        classified: AnalysisOutput? = null,
    ): StageResult {
        val flags = mutableListOf<AnalysisValidationFlag>()
        var failure = AnalysisFailure.INVALID_OUTPUT
        repeat(2) { attempt ->
            val budget = remaining().toNanos()
            if (budget <= 0 || !permits.tryAcquire(budget, TimeUnit.NANOSECONDS)) {
                return StageResult(failure = AnalysisFailure.TIMEOUT, flags = flags)
            }
            val result = try {
                val callBudget = remaining()
                if (callBudget.isNegative || callBudget.isZero) return StageResult(failure = AnalysisFailure.TIMEOUT, flags = flags)
                if (!reserveCall()) return StageResult(failure = AnalysisFailure.RUN_LIMIT_REACHED, flags = flags)
                provider.generate(request.copy(retry = attempt == 1), minOf(callBudget, Duration.ofSeconds(20)))
            } finally {
                permits.release()
            }
            if (remaining().toNanos() <= 0) return StageResult(failure = AnalysisFailure.TIMEOUT, flags = flags)
            when (result) {
                is ProviderResult.Failure -> {
                    failure = result.reason
                    if (request.stage == AnalysisStage.PROPOSE) return StageResult(failure = failure, flags = flags)
                }
                is ProviderResult.Success -> {
                    val validated = try {
                        val root = mapper.reader()
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                                DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                            .readTree(result.json)
                        if (!root.isObject) null else {
                            val combined = root.deepCopy() as ObjectNode
                            if (classified == null) {
                                AnalysisStages.emptyProposal().forEach { (key, value) -> combined.set(key, mapper.valueToTree(value)) }
                            } else {
                                AnalysisStages.classification(classified).forEach { (key, value) -> combined.set(key, mapper.valueToTree(value)) }
                            }
                            validator.validate(mapper.writeValueAsString(combined), options, wishes)
                        }
                    } catch (_: JacksonException) {
                        failure = AnalysisFailure.INVALID_JSON
                        null
                    }
                    if (remaining().toNanos() <= 0) return StageResult(failure = AnalysisFailure.TIMEOUT, flags = flags)
                    when (validated) {
                        is AnalysisValidationResult.Accepted -> return StageResult(validated.output, result.model,
                            flags = flags + validated.flags)
                        is AnalysisValidationResult.Rejected -> {
                            flags += validated.flags
                            failure = AnalysisFailure.INVALID_OUTPUT
                        }
                        null -> Unit
                    }
                }
            }
        }
        return StageResult(failure = failure, flags = flags.toList())
    }

    @Synchronized
    private fun reserveCall(): Boolean {
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        if (capDate != today) {
            capDate = today
            callsToday = 0
        }
        if (callsToday >= properties.dailyRunCap) return false
        callsToday++
        return true
    }
}

@Configuration(proxyBeanMethods = false)
class AnalysisConfiguration {
    @Bean
    fun analysisProperties(environment: Environment): AnalysisProperties = AnalysisProperties.from(environment)

    @Bean
    fun wishAnalyzer(properties: AnalysisProperties, mapper: JsonMapper): WishAnalyzer =
        DefaultWishAnalyzer(properties, OpenAiAnalysisProvider(properties, mapper), mapper)
}

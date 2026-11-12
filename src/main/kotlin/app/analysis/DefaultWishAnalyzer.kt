package app.analysis

import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.port.AnalysisMetrics
import app.campaign.port.AnalysisTokenUsage
import app.campaign.port.AnalysisFailure
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalysisObserver
import app.campaign.port.AnalysisProgressEvent
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
import org.slf4j.LoggerFactory
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

    override fun classify(option: Option, wish: Wish): AnalysisResult {
        val started = nanoTime()
        fun remaining() = Duration.ofNanos(Duration.ofSeconds(15).toNanos() - (nanoTime() - started))
        if (properties.apiKey.isBlank()) return AnalysisResult.Failure(AnalysisFailure.NOT_CONFIGURED)
        if (wish.text.length > 500) return AnalysisResult.Failure(AnalysisFailure.INVALID_OUTPUT)
        val options = listOf(option)
        val wishes = listOf(wish)
        val input = AnalysisPrompt.input(mapper, options, wishes)
        if (input.length > 160000) return AnalysisResult.Failure(AnalysisFailure.INVALID_OUTPUT)
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "wish-match").apply { isDaemon = true }
        }
        val future = executor.submit<StageResult> {
            generate(AnalysisRequest(input, AnalysisStages.schema(options, AnalysisStage.CLASSIFY), false,
                maxOutputTokens = 1024), options, wishes, ::remaining, attempts = 1)
        }
        try {
            val budget = remaining().toNanos()
            if (budget <= 0) return AnalysisResult.Failure(AnalysisFailure.TIMEOUT)
            val result = future.get(budget, TimeUnit.NANOSECONDS)
            if (remaining().toNanos() <= 0) return AnalysisResult.Failure(AnalysisFailure.TIMEOUT, result.flags)
            val output = result.output ?: return AnalysisResult.Failure(result.failure, result.flags)
            return AnalysisResult.Success(result.model, AnalysisValidationResult.Accepted(output, result.flags),
                analysisInputHash(options, wishes), clock.instant())
        } catch (_: TimeoutException) {
            return AnalysisResult.Failure(AnalysisFailure.TIMEOUT)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return AnalysisResult.Failure(AnalysisFailure.PROVIDER_FAILURE)
        } catch (_: ExecutionException) {
            return AnalysisResult.Failure(AnalysisFailure.PROVIDER_FAILURE)
        } finally {
            future.cancel(true)
            executor.shutdownNow()
        }
    }

    override fun analyze(options: List<Option>, wishes: List<Wish>): AnalysisResult =
        analyzeTracked(options, wishes, AnalysisObserver {})

    override fun analyzeTracked(options: List<Option>, wishes: List<Wish>, observer: AnalysisObserver): AnalysisResult {
        val progressLock = Any()
        var observedFailureCategory: String? = null
        fun report(event: AnalysisProgressEvent) = synchronized(progressLock) {
            if (event.failureCategory != null) observedFailureCategory = event.failureCategory
            try {
                observer.onProgress(event)
            } catch (_: Exception) {
                // A progress checkpoint must not discard a valid analysis or trigger extra calls.
            }
        }
        val started = nanoTime()
        val usageLock = Any()
        val usages = mutableListOf<AnalysisTokenUsage?>()
        val failures = mutableListOf<AnalysisFailure>()
        var callsStarted = 0
        val result = runAnalysis(options, wishes, started, ::report,
            onCall = { synchronized(usageLock) { callsStarted++ } }) { returned ->
            synchronized(usageLock) {
                usages += returned.usage
                if (returned is ProviderResult.Failure) failures += returned.reason
            }
        }
        val metrics = synchronized(usageLock) {
            val available = usages.filterNotNull()
            fun total(field: (AnalysisTokenUsage) -> Long?): Long? =
                if (available.isEmpty() || available.any { field(it) == null }) null else available.sumOf { field(it)!! }
            AnalysisMetrics((nanoTime() - started).coerceAtLeast(0) / 1_000_000_000.0,
                if (available.isEmpty()) null else AnalysisTokenUsage(total { it.inputTokens }, total { it.outputTokens },
                    total { it.reasoningTokens }),
                callsStarted == usages.size && usages.isNotEmpty() && usages.all {
                    it != null && it.inputTokens != null && it.outputTokens != null && it.reasoningTokens != null
                },
                ((result as? AnalysisResult.Failure)?.reason ?: failures.firstOrNull())?.let(::failureCategory)
                    ?: synchronized(progressLock) { observedFailureCategory })
        }
        report(AnalysisProgressEvent("RUN_FINISHED", metrics = metrics, failureCategory = metrics.failureCategory))
        LoggerFactory.getLogger(javaClass).info("Analysis result={} elapsedSeconds={} failureCategory={} usageComplete={}",
            result.javaClass.simpleName, metrics.elapsedSeconds, metrics.failureCategory, metrics.usageComplete)
        return when (result) {
            is AnalysisResult.Success -> result.copy(validated = result.validated.copy(
                output = result.validated.output.copy(metrics = metrics)))
            is AnalysisResult.Failure -> result.copy(metrics = metrics)
        }
    }

    private fun runAnalysis(
        options: List<Option>,
        wishes: List<Wish>,
        started: Long,
        report: (AnalysisProgressEvent) -> Unit,
        onCall: () -> Unit,
        onResult: (ProviderResult) -> Unit,
    ): AnalysisResult {
        fun remaining() = Duration.ofNanos(Duration.ofSeconds(120).toNanos() - (nanoTime() - started))
        // Stop classification early enough to synthesize the validated results.
        fun classificationRemaining() = remaining().minusSeconds(20)
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
            val batches = listOf(wishes.chunked(properties.batchSize), secondReadingBatches(wishes))
            val tasks = listOf(mutableListOf<Future<StageResult>>(), mutableListOf<Future<StageResult>>())
            val order = if (properties.concurrency == 1) {
                batches.flatMapIndexed { reading, groups -> groups.indices.map { reading to it } }
            } else (0 until batches.maxOf { it.size }).flatMap { index ->
                batches.indices.filter { index < batches[it].size }.map { it to index }
            }
            for ((reading, index) in order) {
                val batch = batches[reading][index]
                val task = executor.submit<StageResult> {
                    val result = try {
                        generate(AnalysisRequest(AnalysisPrompt.input(mapper, options, batch),
                            AnalysisStages.schema(options, AnalysisStage.CLASSIFY), false), options, batch,
                            ::classificationRemaining, onCall = onCall, onResult = onResult,
                            onValidation = { validationFlags -> report(AnalysisProgressEvent("CLASSIFY_VALIDATED",
                                reading + 1, index + 1, batches[reading].size, flags = validationFlags)) })
                    } catch (_: Exception) {
                        StageResult(failure = AnalysisFailure.PROVIDER_FAILURE)
                    }
                    report(AnalysisProgressEvent("BATCH_COMPLETED", reading + 1, index + 1, batches[reading].size,
                        processedWishIds = result.output?.wishes.orEmpty().map { it.wishId },
                        failureCategory = if (result.output == null) failureCategory(result.failure) else null))
                    result
                }
                tasks[reading] += task
                futures += task
            }
            var batchFailure = AnalysisFailure.INVALID_OUTPUT
            val readings = tasks.mapIndexed { reading, pending ->
                val outputs = pending.mapNotNull { future ->
                    val result = try {
                        if (future.isDone) future.get() else {
                            val budget = classificationRemaining().toNanos()
                            if (budget <= 0) StageResult(failure = AnalysisFailure.TIMEOUT)
                            else future.get(budget, TimeUnit.NANOSECONDS)
                        }
                    } catch (_: TimeoutException) {
                        StageResult(failure = AnalysisFailure.TIMEOUT)
                    }
                    if (result.output == null) {
                        batchFailure = result.failure
                        report(AnalysisProgressEvent("READING_FAILED", reading + 1,
                            failureCategory = failureCategory(result.failure)))
                    }
                    flags += result.flags
                    result.output
                }
                AnalysisOutput(outputs.flatMap { it.wishes }, outputs.flatMap { it.verdicts }, null,
                    "No proposal selected.", emptyList(), emptyList(), emptyList(), options.map { it.id })
            }
            futures.filterNot { it.isDone }.forEach { it.cancel(true) }
            val merged = SecondReadingMerger.merge(options, wishes, readings[0], readings[1])
            val analyzedIds = merged.wishes.map { it.wishId }.toSet()
            val analyzedWishes = wishes.filter { it.id in analyzedIds }
            val missing = wishes.filter { it.id !in analyzedIds }.map { unanalyzed(it) }
            val incomplete = merged.secondReading!!.incompleteWishIds.map { id ->
                AnalysisValidationFlag(AnalysisValidationRule.INCOMPLETE_SECOND_READING,
                    "wishes[$id]", "Needs your eyes: a complete second reading is unavailable.")
            }
            if (analyzedWishes.isEmpty()) return AnalysisResult.Failure(batchFailure, (flags + missing).distinct())
            flags += missing + incomplete
            report(AnalysisProgressEvent("READINGS_MERGED", processedWishIds = analyzedIds.toList(), flags = missing + incomplete))
            val proposalInput = AnalysisStages.proposalInput(mapper, options, merged)
            if (proposalInput.length > 160000) return AnalysisResult.Failure(AnalysisFailure.INVALID_OUTPUT, flags)
            report(AnalysisProgressEvent("PROPOSAL_STARTED"))
            val proposalTask = executor.submit<StageResult> { generate(AnalysisRequest(
                proposalInput,
                AnalysisStages.schema(options, AnalysisStage.PROPOSE), false, AnalysisStage.PROPOSE, 2048,
            ), options, analyzedWishes, ::remaining, merged, onCall = onCall, onResult = onResult,
                onValidation = { validationFlags -> report(AnalysisProgressEvent("PROPOSAL_VALIDATED", flags = validationFlags)) }) }
            futures += proposalTask
            val proposalBudget = remaining().toNanos()
            if (proposalBudget <= 0) return AnalysisResult.Failure(AnalysisFailure.TIMEOUT, flags)
            val proposal = proposalTask.get(proposalBudget, TimeUnit.NANOSECONDS)
            flags += proposal.flags
            if (remaining().isNegative || remaining().isZero) return AnalysisResult.Failure(AnalysisFailure.TIMEOUT, flags)
            val output = proposal.output ?: return AnalysisResult.Failure(proposal.failure, flags)
            return AnalysisResult.Success(proposal.model,
                AnalysisValidationResult.Accepted(output.copy(secondReading = merged.secondReading), flags.distinct()),
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

    private fun failureCategory(reason: AnalysisFailure): String = when (reason) {
        AnalysisFailure.TIMEOUT -> "TIMEOUT"
        AnalysisFailure.INVALID_JSON, AnalysisFailure.INVALID_OUTPUT -> "VALIDATION"
        AnalysisFailure.PROVIDER_FAILURE -> "PROVIDER"
        AnalysisFailure.RUN_LIMIT_REACHED -> "LIMIT"
        AnalysisFailure.NOT_CONFIGURED -> "CONFIGURATION"
    }

    private fun secondReadingBatches(wishes: List<Wish>): List<List<Wish>> {
        if (wishes.size <= 1) return wishes.map { listOf(it) }
        val rotated = wishes.drop(1) + wishes.first()
        if (properties.batchSize == 1) {
            val batches = rotated.chunked(2).map { it.toMutableList() }.toMutableList()
            if (batches.last().size == 1 && batches.size > 1) {
                val last = batches.removeAt(batches.lastIndex)
                batches.last().addAll(last)
            }
            return batches
        }
        val size = minOf(properties.batchSize, wishes.size - 1)
        return rotated.chunked(size)
    }

    private fun unanalyzed(wish: Wish) = AnalysisValidationFlag(AnalysisValidationRule.UNANALYZED_WISH,
        "wishes[${wish.id}]", "Wish has no validated first reading.")

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
        attempts: Int = 2,
        onValidation: (List<AnalysisValidationFlag>) -> Unit = {},
        onCall: () -> Unit = {},
        onResult: (ProviderResult) -> Unit = {},
    ): StageResult {
        val flags = mutableListOf<AnalysisValidationFlag>()
        var failure = AnalysisFailure.INVALID_OUTPUT
        repeat(attempts) { attempt ->
            val budget = remaining().toNanos()
            if (budget <= 0 || !permits.tryAcquire(budget, TimeUnit.NANOSECONDS)) {
                return StageResult(failure = AnalysisFailure.TIMEOUT, flags = flags)
            }
            val result = try {
                val callBudget = remaining()
                if (callBudget.isNegative || callBudget.isZero) return StageResult(failure = AnalysisFailure.TIMEOUT, flags = flags)
                if (!reserveCall()) return StageResult(failure = AnalysisFailure.RUN_LIMIT_REACHED, flags = flags)
                onCall()
                provider.generate(request.copy(retry = attempt == 1), minOf(callBudget, Duration.ofSeconds(20))).also(onResult)
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
                    if (validated != null) onValidation(validated.flags)
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

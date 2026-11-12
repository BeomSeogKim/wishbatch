package app.analysis

import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.model.WishSource
import app.campaign.port.AnalysisFailure
import app.campaign.port.AnalysisOutput
import app.campaign.port.SecondReadingStatus
import app.campaign.port.AnalysisTokenUsage
import app.campaign.port.AnalysisObserver
import app.campaign.port.AnalysisResult
import app.campaign.port.AnalysisValidationRule
import app.campaign.rules.analysisInputHash
import app.campaign.rules.optionWishCounts
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class SecondReadingTests {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val workspaceId = UUID(0, 1)
    private val option = Option(workspaceId, "Cards", "postcard", emptyMap(), emptyMap(), 100, "USD", 5, 20, "Two weeks", "US")

    @Test
    fun `second reading changes every available wish context deterministically including small batches`() {
        for ((size, batchSize) in listOf(2 to 1, 3 to 1, 5 to 1, 3 to 10, 5 to 4, 8 to 4, 9 to 4, 11 to 10)) {
            val wishes = wishes(size)
            val runs = (1..2).map {
                val inputs = mutableListOf<List<String>>()
                val output = analyze(wishes, batchSize) { request, _ ->
                    if (request.stage == AnalysisStage.CLASSIFY) inputs += ids(request)
                    respond(request)
                }
                val firstBatchCount = (size + batchSize - 1) / batchSize
                val first = inputs.take(firstBatchCount)
                val second = inputs.drop(firstBatchCount)
                assertEquals(wishes.map { it.id.toString() }.toSet(), second.flatten().toSet())
                assertEquals(size, second.flatten().size)
                assertTrue(second.all { it.size <= 10 })
                wishes.forEach { wish ->
                    val id = wish.id.toString()
                    assertTrue(first.single { id in it }.toSet() != second.single { id in it }.toSet(),
                        "Wish context must change for size=$size batchSize=$batchSize")
                }
                val agreement = output.secondReading!!
                assertTrue(agreement.batchCompositionChanged)
                assertEquals(size, agreement.agreedWishCount)
                assertEquals(size, agreement.comparedWishCount)
                inputs
            }
            assertEquals(runs[0], runs[1])
        }
    }

    @Test
    fun `single wish is read twice without claiming a changed batch composition`() {
        val inputs = mutableListOf<List<String>>()
        val output = analyze(wishes(1)) { request, _ ->
            if (request.stage == AnalysisStage.CLASSIFY) inputs += ids(request)
            respond(request)
        }
        assertEquals(2, inputs.size)
        assertEquals(inputs[0], inputs[1])
        assertFalse(output.secondReading!!.batchCompositionChanged)
        assertEquals(1, output.secondReading.agreedWishCount)
    }

    @Test
    fun `disagreement becomes conditional before proposal counts and preserves both verdicts`() {
        val wishes = wishes(4)
        var classifications = 0
        val output = analyze(wishes) { request, _ ->
            if (request.stage == AnalysisStage.CLASSIFY) {
                classifications++
                classification(request, verdict = { id ->
                    if (classifications > 2 && id == wishes[1].id.toString()) "INCOMPATIBLE" else "COMPATIBLE"
                })
            } else {
                val counts = mapper.readTree(request.input)["counts"][0]
                assertEquals(3, counts["compatible"].asInt())
                assertEquals(1, counts["depends"].asInt())
                respond(request)
            }
        }
        assertEquals(CompatibilityVerdict.CONDITIONAL, output.verdicts.single { it.wishId == wishes[1].id }.verdict)
        val comparison = output.secondReading!!.comparisons.single { it.wishId == wishes[1].id }
        assertTrue(comparison.needsYourEyes)
        assertEquals(CompatibilityVerdict.COMPATIBLE, comparison.firstVerdict)
        assertEquals(CompatibilityVerdict.INCOMPATIBLE, comparison.secondVerdict)
        assertEquals(3, output.secondReading.agreedWishCount)
        assertEquals(4, output.secondReading.comparedWishCount)
        assertEquals(output, mapper.readValue(mapper.writeValueAsString(output), AnalysisOutput::class.java))
    }

    @Test
    fun `request kind disagreement remains reviewable in either direction`() {
        for (requestFirst in listOf(true, false)) {
            val wishes = wishes(2)
            var classifications = 0
            val output = analyze(wishes) { request, _ ->
                if (request.stage == AnalysisStage.CLASSIFY) {
                    classifications++
                    classification(request, kind = { id ->
                        if (id == wishes.first().id.toString() && ((classifications == 1) != requestFirst)) "NOT_A_REQUEST"
                        else "REQUEST"
                    })
                } else respond(request)
            }
            assertEquals(WishKind.REQUEST, output.wishes.first().kind)
            assertEquals(CompatibilityVerdict.CONDITIONAL, output.verdicts.first().verdict)
            assertEquals(1, optionWishCounts(output).single().compatible)
            assertEquals(1, output.secondReading!!.agreedWishCount)
            assertTrue(output.secondReading.comparisons.first().needsYourEyes)
        }
    }

    @Test
    fun `missing second reading preserves first verdicts without inventing agreement`() {
        val wishes = wishes(4)
        var classifications = 0
        val analyzer = analyzer { request, _ ->
            if (request.stage == AnalysisStage.CLASSIFY) {
                classifications++
                if (classifications > 2 && wishes[1].id.toString() in ids(request)) {
                    ProviderResult.Failure(AnalysisFailure.PROVIDER_FAILURE)
                } else respond(request)
            } else respond(request)
        }
        val result = assertIs<AnalysisResult.Success>(analyzer.analyze(listOf(option), wishes))
        val output = result.validated.output
        assertEquals(wishes.map { it.id }, output.wishes.map { it.wishId })
        assertEquals(4, optionWishCounts(output).single().compatible)
        assertEquals(2, output.secondReading!!.comparedWishCount)
        assertEquals(setOf("wishes[${wishes[1].id}]", "wishes[${wishes[2].id}]"), result.validated.flags
            .filter { it.rule == AnalysisValidationRule.INCOMPLETE_SECOND_READING }.map { it.path }.toSet())
        assertEquals(analysisInputHash(listOf(option), wishes), result.inputHash)
    }

    @Test
    fun `second reading shares the original deadline and daily provider cap`() {
        val nanos = AtomicLong()
        var classifications = 0
        val timed = analyzer(nanoTime = nanos::get) { request, _ ->
            if (request.stage == AnalysisStage.CLASSIFY && ++classifications == 2) nanos.set(Duration.ofSeconds(120).toNanos())
            respond(request)
        }
        assertEquals(AnalysisFailure.TIMEOUT,
            assertIs<AnalysisResult.Failure>(timed.analyze(listOf(option), wishes(1))).reason)
        var calls = 0
        val capped = analyzer(cap = 1) { request, _ -> calls++; respond(request) }
        assertEquals(AnalysisFailure.RUN_LIMIT_REACHED,
            assertIs<AnalysisResult.Failure>(capped.analyze(listOf(option), wishes(1))).reason)
        assertEquals(1, calls)
    }

    @Test
    fun `both readings start concurrently within one provider concurrency limit`() {
        val wishes = wishes(4)
        val secondEntered = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val firstIds = wishes.take(2).map { it.id.toString() }.toSet()
        val secondIds = wishes.slice(1..2).map { it.id.toString() }.toSet()
        val output = assertIs<AnalysisResult.Success>(analyzer(concurrency = 2) { request, _ ->
            val count = active.incrementAndGet()
            peak.accumulateAndGet(count, ::maxOf)
            try {
                if (request.stage == AnalysisStage.CLASSIFY) {
                    val ids = ids(request).toSet()
                    if (ids == secondIds) secondEntered.countDown()
                    if (ids == firstIds) assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
                }
                respond(request)
            } finally {
                active.decrementAndGet()
            }
        }.analyze(listOf(option), wishes)).validated.output
        assertEquals(2, peak.get())
        assertEquals(SecondReadingStatus.COMPLETE, output.secondReading!!.status)
        assertEquals(4, output.secondReading.comparedWishCount)
    }

    @Test
    fun `failed entire second reading retains complete first roster and flags every unpaired wish`() {
        val wishes = wishes(4)
        val firstBatches = wishes.chunked(2).map { batch -> batch.map { it.id.toString() }.toSet() }
        for (failure in listOf(AnalysisFailure.TIMEOUT, AnalysisFailure.PROVIDER_FAILURE, AnalysisFailure.INVALID_OUTPUT)) {
            val result = assertIs<AnalysisResult.Success>(analyzer { request, _ ->
                if (request.stage == AnalysisStage.CLASSIFY && ids(request).toSet() !in firstBatches) {
                    if (failure == AnalysisFailure.INVALID_OUTPUT) ProviderResult.Success("test-model", "{}")
                    else ProviderResult.Failure(failure)
                } else respond(request)
            }.analyze(listOf(option), wishes))
            val output = result.validated.output
            assertEquals(wishes.map { it.id }, output.wishes.map { it.wishId })
            assertEquals(4, output.verdicts.size)
            assertEquals(4, optionWishCounts(output).single().compatible)
            assertEquals(SecondReadingStatus.INCOMPLETE, output.secondReading!!.status)
            assertEquals(wishes.map { it.id }, output.secondReading.incompleteWishIds)
            assertEquals(0, output.secondReading.comparedWishCount)
            assertEquals(0, output.secondReading.agreedWishCount)
            assertTrue(output.secondReading.comparisons.isEmpty())
            assertEquals(wishes.map { "wishes[${it.id}]" }, result.validated.flags.filter {
                it.rule == AnalysisValidationRule.INCOMPLETE_SECOND_READING
            }.map { it.path })
            assertEquals(when (failure) {
                AnalysisFailure.TIMEOUT -> "TIMEOUT"
                AnalysisFailure.INVALID_OUTPUT -> "VALIDATION"
                else -> "PROVIDER"
            }, output.metrics!!.failureCategory)
        }
    }

    @Test
    fun `classification cutoff reserves synthesis time and retains first reading after slow second`() {
        val nanos = AtomicLong()
        var calls = 0
        var proposalBudget: Duration? = null
        val result = assertIs<AnalysisResult.Success>(analyzer(nanoTime = nanos::get) { request, budget ->
            if (request.stage == AnalysisStage.CLASSIFY && ++calls == 2) {
                nanos.set(Duration.ofSeconds(100).toNanos())
            }
            if (request.stage == AnalysisStage.PROPOSE) proposalBudget = budget
            respond(request)
        }.analyze(listOf(option), wishes(1)))
        assertEquals(Duration.ofSeconds(20), proposalBudget)
        assertEquals(1, result.validated.output.wishes.size)
        assertEquals(SecondReadingStatus.INCOMPLETE, result.validated.output.secondReading!!.status)
        assertEquals(0, result.validated.output.secondReading!!.comparedWishCount)
        assertEquals(100.0, result.validated.output.metrics!!.elapsedSeconds)
        assertNull(result.validated.output.metrics!!.tokenUsage)
    }

    @Test
    fun `slow second reading is cancelled while completed first reading still reaches synthesis`() {
        val nanos = AtomicLong()
        val firstCompleted = CountDownLatch(1)
        val cutoffReached = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val analyzer = analyzer(concurrency = 2, nanoTime = nanos::get) { request, _ ->
            if (request.stage == AnalysisStage.CLASSIFY && ids(request).size == 1) {
                assertTrue(firstCompleted.await(5, TimeUnit.SECONDS))
                nanos.set(Duration.ofSeconds(100).toNanos())
                cutoffReached.countDown()
                try {
                    CountDownLatch(1).await(5, TimeUnit.SECONDS)
                    error("Slow reading must be cancelled")
                } catch (_: InterruptedException) {
                    cancelled.countDown()
                    ProviderResult.Failure(AnalysisFailure.TIMEOUT)
                }
            } else respond(request)
        }
        val result = assertIs<AnalysisResult.Success>(analyzer.analyzeTracked(listOf(option), wishes(2), AnalysisObserver {
            if (it.stage == "BATCH_COMPLETED" && it.reading == 1) {
                firstCompleted.countDown()
                assertTrue(cutoffReached.await(5, TimeUnit.SECONDS))
            }
        }))
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        assertEquals(2, result.validated.output.wishes.size)
        assertEquals(SecondReadingStatus.INCOMPLETE, result.validated.output.secondReading!!.status)
        assertEquals(0, result.validated.output.secondReading!!.comparedWishCount)
        assertEquals("TIMEOUT", result.validated.output.metrics!!.failureCategory)
        assertTrue(result.validated.flags.any { it.rule == AnalysisValidationRule.INCOMPLETE_SECOND_READING })
    }

    @Test
    fun `elapsed time and token totals use returned measurements and mark missing usage unavailable`() {
        for (missing in listOf(false, true)) {
            val nanos = AtomicLong()
            var calls = 0
            val result = assertIs<AnalysisResult.Success>(analyzer(nanoTime = nanos::get) { request, _ ->
                nanos.addAndGet(Duration.ofSeconds(1).toNanos())
                val response = respond(request) as ProviderResult.Success
                response.copy(usage = if (missing && ++calls == 1) null else AnalysisTokenUsage(10, 5, 2))
            }.analyze(listOf(option), wishes(1)))
            val metrics = result.validated.output.metrics!!
            assertEquals(3.0, metrics.elapsedSeconds)
            assertEquals(AnalysisTokenUsage(if (missing) 20 else 30, if (missing) 10 else 15,
                if (missing) 4 else 6), metrics.tokenUsage)
            assertEquals(!missing, metrics.usageComplete)
        }
    }

    @Test
    fun `old stored output has no second reading until reanalysis`() {
        val output = AnalysisOutput(emptyList(), emptyList(), null, "No request.", emptyList(), emptyList(), emptyList(), listOf(option.id))
        val json = mapper.readTree(mapper.writeValueAsString(output)) as tools.jackson.databind.node.ObjectNode
        json.remove("secondReading")
        assertNull(mapper.readValue(mapper.writeValueAsString(json), AnalysisOutput::class.java).secondReading)
    }

    private fun wishes(size: Int) = (1..size).map {
        Wish(workspaceId, "Cards please $it", WishSource.ONSITE, createdAt = now, id = UUID(1, it.toLong()))
    }

    private fun ids(request: AnalysisRequest) = mapper.readTree(request.input)["wishes"].toList().map { it["wish_id"].asString() }

    private fun classification(
        request: AnalysisRequest,
        kind: (String) -> String = { "REQUEST" },
        verdict: (String) -> String = { "COMPATIBLE" },
    ): ProviderResult.Success {
        val ids = ids(request)
        return ProviderResult.Success("test-model", mapper.writeValueAsString(mapOf(
            "wishes" to ids.map { mapOf("wish_id" to it, "kind" to kind(it), "needs" to emptyList<Any>(),
                "max_unit_price" to null, "quantity" to null, "region" to null) },
            "verdicts" to ids.filter { kind(it) == "REQUEST" }.map { mapOf("wish_id" to it, "option_id" to option.id,
                "verdict" to verdict(it), "reason" to "Matches request.") },
        )))
    }

    private fun respond(request: AnalysisRequest): ProviderResult = if (request.stage == AnalysisStage.CLASSIFY) classification(request)
    else ProviderResult.Success("test-model", mapper.writeValueAsString(mapOf(
        "proposal" to null, "no_proposal_reason" to "No proposal selected.", "not_served" to emptyList<Any>(),
        "questions_for_maker" to emptyList<Any>(), "alternatives" to emptyList<Any>(),
    )))

    private fun analyze(wishes: List<Wish>, batchSize: Int = 2, respond: (AnalysisRequest, Duration) -> ProviderResult): AnalysisOutput =
        assertIs<AnalysisResult.Success>(analyzer(batchSize = batchSize, respond = respond).analyze(listOf(option), wishes)).validated.output

    private fun analyzer(
        batchSize: Int = 2,
        cap: Int = 100,
        concurrency: Int = 1,
        nanoTime: () -> Long = { 0L },
        respond: (AnalysisRequest, Duration) -> ProviderResult,
    ) = DefaultWishAnalyzer(AnalysisProperties("test", batchSize = batchSize, concurrency = concurrency, dailyRunCap = cap),
        AnalysisProvider { request, budget -> respond(request, budget) }, mapper, Clock.fixed(now, ZoneOffset.UTC), nanoTime)
}

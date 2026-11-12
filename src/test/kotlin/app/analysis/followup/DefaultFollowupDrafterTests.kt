package app.analysis.followup

import app.analysis.AnalysisProperties
import app.analysis.AnalysisProvider
import app.analysis.AnalysisRequest
import app.analysis.AnalysisStage
import app.analysis.ProviderResult
import app.campaign.followup.FollowupDraftResult
import app.campaign.followup.FollowupAnswer
import app.campaign.followup.FollowupQuestions
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.NeedAttribute
import app.campaign.model.NeedStrength
import app.campaign.model.Option
import app.campaign.model.WishKind
import app.campaign.port.AnalysisFailure
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalyzedWish
import app.campaign.port.WishNeed
import app.campaign.port.WishVerdict
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefaultFollowupDrafterTests {
    private val mapper = JsonMapper.builder().build()
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val wish = UUID.randomUUID()
    private val option = Option(UUID.randomUUID(), "Art print", "print", emptyMap(),
        mapOf("finish" to listOf("matte", "glossy")), 500, "USD", 3, 10, "Maker terms", "US")
    private val output = AnalysisOutput(
        listOf(AnalyzedWish(wish, WishKind.REQUEST,
            listOf(WishNeed(NeedStrength.HARD, NeedAttribute.FINISH, "matte", "Only matte")))),
        listOf(WishVerdict(wish, option.id, CompatibilityVerdict.CONDITIONAL, "Confirm finish.")),
        null, "Review conditions.", emptyList(), emptyList(), emptyList(), listOf(option.id),
    )

    @Test
    fun `one bounded proposal-stage call returns validated questions without commercial fields`() {
        val provider = RecordingProvider()
        val result = assertIs<FollowupDraftResult.Drafted>(drafter(provider).draft(option, output))
        assertEquals("reported-model", result.model)
        assertEquals("finish", result.drafts.single().variantKey)
        assertEquals(listOf("matte", "glossy"), result.drafts.single().choices)
        assertEquals(listOf(wish), result.drafts.single().wishIds)
        val request = provider.requests.single()
        assertFalse(request.retry)
        assertEquals(AnalysisStage.PROPOSE, request.stage)
        assertEquals(1536, request.maxOutputTokens)
        assertEquals(Duration.ofSeconds(15), provider.budgets.single())
        val input = mapper.readTree(request.input)
        assertTrue(input.path("task").asString().contains("questions_for_maker"))
        assertEquals(wish.toString(), input.path("conditions")[0].path("wish_id").asString())
        assertFalse(request.input.contains("unit_cost"))
        assertFalse(request.input.contains("min_batch"))
        assertEquals(setOf("proposal", "no_proposal_reason", "not_served", "questions_for_maker", "alternatives"),
            (request.schema.getValue("properties") as Map<*, *>).keys)
    }

    @Test
    fun `paper finish and explicit color conditions draft catalog choices and fan answers use no model call`() {
        for ((key, attribute, choices) in listOf(
            Triple("paper", NeedAttribute.FINISH, listOf("matte", "satin")),
            Triple("color", NeedAttribute.OTHER, listOf("blue", "red")),
        )) {
            val mappedOption = Option(option.workspaceId, option.name, option.format, emptyMap(), mapOf(key to choices),
                option.unitCost, option.currency, option.minBatch, option.maxCapacity, option.leadTimeText, option.shipRegion, option.id)
            val condition = if (key == "color") "blue color" else "matte paper"
            val mappedOutput = output.copy(wishes = listOf(output.wishes.single().copy(needs = listOf(
                WishNeed(NeedStrength.HARD, attribute, condition, "Only $condition"),
            ))))
            val reply = mapper.writeValueAsString(mapOf(
                "proposal" to null, "no_proposal_reason" to "Draft questions only.", "not_served" to emptyList<String>(),
                "questions_for_maker" to listOf(mapOf(
                    "text" to "Which $key works for you?", "attribute" to attribute.name.lowercase(),
                    "variant_key" to key, "choices" to choices, "wish_ids" to listOf(wish.toString()),
                )), "alternatives" to emptyList<String>(),
            ))
            val provider = RecordingProvider { _, _ -> ProviderResult.Success("reported-model", reply) }
            val drafted = assertIs<FollowupDraftResult.Drafted>(drafter(provider).draft(mappedOption, mappedOutput))
            assertEquals(key, drafted.drafts.single().variantKey)
            assertEquals(attribute, drafted.drafts.single().attribute)
            assertEquals(choices, drafted.drafts.single().choices)
            val input = mapper.readTree(provider.requests.single().input)
            assertTrue(input.path("option").path("allowed_variants").has(key))
            assertEquals(attribute.name.lowercase(), input.path("conditions")[0].path("needs")[0].path("attribute").asString())
            assertTrue(provider.requests.single().input.contains("paper map to finish"))
            assertTrue(mapper.writeValueAsString(provider.requests.single().schema).contains("other"))
            val run = UUID.randomUUID()
            val approved = FollowupQuestions.approve(run, drafted.drafts, mappedOption, mappedOutput)
            val answer = FollowupAnswer(UUID.randomUUID(), approved.single().id, wish, choices.last())
            assertEquals(CompatibilityVerdict.COMPATIBLE,
                FollowupQuestions.resolve(run, wish, approved, listOf(answer), mappedOption, mappedOutput).verdict)
            assertEquals(1, provider.requests.size)
        }
    }

    @Test
    fun `unrelated other conditions do not trigger color drafting`() {
        val colorOption = Option(option.workspaceId, option.name, option.format, emptyMap(), mapOf("color" to listOf("blue", "red")),
            option.unitCost, option.currency, option.minBatch, option.maxCapacity, option.leadTimeText, option.shipRegion, option.id)
        val unrelated = output.copy(wishes = listOf(output.wishes.single().copy(needs = listOf(
            WishNeed(NeedStrength.HARD, NeedAttribute.OTHER, "gift wrapping", "Only gift wrapped"),
        ))))
        val provider = RecordingProvider()
        assertTrue(assertIs<FollowupDraftResult.Drafted>(drafter(provider).draft(colorOption, unrelated)).drafts.isEmpty())
        assertTrue(provider.requests.isEmpty())
    }

    @Test
    fun `invalid model drafts and provider failures are rejected without retry`() {
        val valid = response()
        for ((reply, failure) in listOf(
            "{" to AnalysisFailure.INVALID_JSON,
            "$valid {}" to AnalysisFailure.INVALID_JSON,
            "[]" to AnalysisFailure.INVALID_OUTPUT,
            valid.replace("glossy", "metallic") to AnalysisFailure.INVALID_OUTPUT,
            valid.replace("Which finish works for you?", "Would you pay twenty dollars?") to AnalysisFailure.INVALID_OUTPUT,
            valid.replace("\"attribute\":\"finish\"", "\"attribute\":\"price\"") to AnalysisFailure.INVALID_OUTPUT,
            valid.replace(wish.toString(), UUID.randomUUID().toString()) to AnalysisFailure.INVALID_OUTPUT,
            valid.replace("\"proposal\":null", "\"proposal\":{}") to AnalysisFailure.INVALID_OUTPUT,
            valid.replace("\"choices\":[", "\"choices\":null,\"ignored\":[") to AnalysisFailure.INVALID_OUTPUT,
        )) {
            val provider = RecordingProvider { _, _ -> ProviderResult.Success("reported-model", reply) }
            assertFailure(failure, drafter(provider).draft(option, output))
            assertEquals(1, provider.requests.size)
        }
        for (failure in listOf(AnalysisFailure.TIMEOUT, AnalysisFailure.PROVIDER_FAILURE)) {
            val provider = RecordingProvider { _, _ -> ProviderResult.Failure(failure) }
            assertFailure(failure, drafter(provider).draft(option, output))
            assertEquals(1, provider.requests.size)
        }
        val throwing = RecordingProvider { _, _ -> throw IllegalStateException("Unavailable") }
        assertFailure(AnalysisFailure.PROVIDER_FAILURE, drafter(throwing).draft(option, output))
    }

    @Test
    fun `missing key disabled cap and wishes without variant conditions make no provider calls`() {
        val provider = RecordingProvider()
        assertFailure(AnalysisFailure.NOT_CONFIGURED, drafter(provider, AnalysisProperties()).draft(option, output))
        assertFailure(AnalysisFailure.RUN_LIMIT_REACHED,
            drafter(provider, AnalysisProperties("test", dailyRunCap = 0)).draft(option, output))
        for (noCondition in listOf(
            output.copy(verdicts = output.verdicts.map { it.copy(verdict = CompatibilityVerdict.COMPATIBLE) }),
            output.copy(wishes = output.wishes.map { it.copy(kind = WishKind.NOT_A_REQUEST) }),
            output.copy(wishes = output.wishes.map { it.copy(needs = it.needs.map { need -> need.copy(strength = NeedStrength.PREFERENCE) }) }),
            output.copy(wishes = output.wishes.map { it.copy(needs = it.needs.map { need -> need.copy(attribute = NeedAttribute.PRICE) }) }),
        )) assertTrue(assertIs<FollowupDraftResult.Drafted>(drafter(provider).draft(option, noCondition)).drafts.isEmpty())
        assertFailure(AnalysisFailure.INVALID_OUTPUT,
            drafter(provider).draft(option, output.copy(optionIds = emptyList())))
        assertTrue(provider.requests.isEmpty())
    }

    @Test
    fun `follow-up daily cap is separately bounded by twenty and configured provider cap and resets at UTC midnight`() {
        for (cap in listOf(2, 100)) {
            val provider = RecordingProvider { _, _ -> ProviderResult.Failure(AnalysisFailure.PROVIDER_FAILURE) }
            val clock = MutableClock(now)
            val drafter = drafter(provider, AnalysisProperties("test", dailyRunCap = cap), clock)
            repeat(minOf(20, cap)) { assertFailure(AnalysisFailure.PROVIDER_FAILURE, drafter.draft(option, output)) }
            assertFailure(AnalysisFailure.RUN_LIMIT_REACHED, drafter.draft(option, output))
            assertEquals(minOf(20, cap), provider.requests.size)
            clock.now = now.plusSeconds(86400)
            assertFailure(AnalysisFailure.PROVIDER_FAILURE, drafter.draft(option, output))
            assertEquals(minOf(20, cap) + 1, provider.requests.size)
        }
    }

    @Test
    fun `late success is rejected by the whole-operation deadline`() {
        val nanos = AtomicLong()
        val provider = RecordingProvider { _, _ ->
            nanos.set(Duration.ofSeconds(15).toNanos())
            ProviderResult.Success("reported-model", response())
        }
        assertFailure(AnalysisFailure.TIMEOUT, drafter(provider, nanoTime = nanos::get).draft(option, output))
        assertEquals(1, provider.requests.size)
        assertEquals(Duration.ofSeconds(15), provider.budgets.single())
    }

    @Test
    fun `inputs and output remain bounded`() {
        val provider = RecordingProvider()
        val manyWishes = (1..200).map {
            output.wishes.single().copy(wishId = UUID.randomUUID(), needs = output.wishes.single().needs.map { need ->
                need.copy(quote = "Only matte " + "q".repeat(500))
            })
        }
        val large = output.copy(wishes = manyWishes, verdicts = manyWishes.map {
            WishVerdict(it.wishId, option.id, CompatibilityVerdict.CONDITIONAL, "Confirm finish.")
        })
        val emptyProvider = RecordingProvider { _, _ -> ProviderResult.Success("reported-model", response(empty = true)) }
        assertIs<FollowupDraftResult.Drafted>(drafter(emptyProvider).draft(option, large))
        val input = mapper.readTree(emptyProvider.requests.single().input)
        assertEquals(40, input.path("conditions").size())
        assertTrue(emptyProvider.requests.single().input.length <= 32000)
        val oversized = option.allowedVariants.mapValues { listOf("x".repeat(32001), "matte") }
        val largeOption = Option(option.workspaceId, option.name, option.format, emptyMap(), oversized,
            option.unitCost, option.currency, option.minBatch, option.maxCapacity, option.leadTimeText, option.shipRegion, option.id)
        assertFailure(AnalysisFailure.INVALID_OUTPUT, drafter(provider).draft(largeOption, output))
        assertTrue(provider.requests.isEmpty())
        val oversizedReply = RecordingProvider { _, _ -> ProviderResult.Success("reported-model", " ".repeat(16001)) }
        assertFailure(AnalysisFailure.INVALID_OUTPUT, drafter(oversizedReply).draft(option, output))
    }

    private fun response(empty: Boolean = false) = """{"proposal":null,"no_proposal_reason":"Draft questions only.","not_served":[],"questions_for_maker":${
        if (empty) "[]" else """[{"text":"Which finish works for you?","attribute":"finish","variant_key":"finish","choices":["matte","glossy"],"wish_ids":["$wish"]}]"""
    },"alternatives":[]}"""

    private fun drafter(
        provider: AnalysisProvider,
        properties: AnalysisProperties = AnalysisProperties("test"),
        clock: Clock = Clock.fixed(now, ZoneOffset.UTC),
        nanoTime: () -> Long = { 0L },
    ) = DefaultFollowupDrafter(properties, provider, mapper, clock, nanoTime)

    private fun assertFailure(expected: AnalysisFailure, result: FollowupDraftResult) =
        assertEquals(expected, assertIs<FollowupDraftResult.Failed>(result).reason)

    private inner class RecordingProvider(
        private val reply: (AnalysisRequest, Duration) -> ProviderResult = { _, _ -> ProviderResult.Success("reported-model", response()) },
    ) : AnalysisProvider {
        val requests = CopyOnWriteArrayList<AnalysisRequest>()
        val budgets = CopyOnWriteArrayList<Duration>()
        override fun generate(request: AnalysisRequest, budget: Duration): ProviderResult {
            requests += request
            budgets += budget
            return reply(request, budget)
        }
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}

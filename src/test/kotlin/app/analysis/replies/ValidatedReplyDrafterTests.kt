package app.analysis.replies

import app.analysis.AnalysisProperties
import app.analysis.AnalysisProvider
import app.analysis.AnalysisRequest
import app.analysis.ProviderResult
import app.campaign.port.AnalysisFailure
import app.campaign.replies.ReplyDraftResult
import app.campaign.replies.ReplyEvidenceGroup
import app.campaign.replies.ReplySafety
import app.campaign.replies.ReplySource
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ValidatedReplyDrafterTests {
    private val mapper = JsonMapper.builder().build()
    private val group = ReplyEvidenceGroup("format", listOf(
        ReplySource(UUID(0, 1), "I want 12 stickers under $20 by Friday."),
    ), listOf("Requested format differs from this batch."))

    @Test
    fun `references retain exact numeric fan evidence separately from generated body`() {
        val provider = RecordingProvider()
        val result = assertIs<ReplyDraftResult.Success>(drafter(provider).draft(listOf(group)))
        assertEquals(mapOf(group.groupId to ReplySafety.body(group.reasons.single())), result.bodies)
        assertEquals("reported-model", result.model)
        assertEquals(group.sources.single().quote,
            mapper.readTree(provider.requests.single().input).path("groups")[0].path("sources")[0].path("quote").asString())
        assertFalse(result.bodies.values.single().contains("12"))
        assertEquals(Duration.ofSeconds(15), provider.budgets.single())
        assertEquals(2048, provider.requests.single().maxOutputTokens)
        assertFalse(provider.requests.single().retry)
    }

    @Test
    fun `fabricated reasons quotes prose and unknown group references are rejected`() {
        val invalid = listOf(
            """{"replies":[{"group_id":"format","reason_index":1,"source_indices":[0]}]}""",
            """{"replies":[{"group_id":"foreign","reason_index":0,"source_indices":[0]}]}""",
            """{"replies":[{"group_id":"format","reason_index":0,"source_indices":[1]}]}""",
            """{"replies":[{"group_id":"format","reason_index":0,"source_indices":[0],"quote":"Made up"}]}""",
            """{"replies":[{"group_id":"format","reason_index":0,"source_indices":[0],"body":"We will sell it for $12 tomorrow"}]}""",
            """{"replies":[{"group_id":"format","reason_index":0,"source_indices":[]}]}""",
            """{"replies":[{"group_id":"format","reason_index":0.5,"source_indices":[0]}]}""",
            """{"replies":[{"group_id":"format","reason_index":0,"source_indices":[0,0]}]}""",
            """{"replies":[],"reason":"Invented"}""",
        )
        for (json in invalid) {
            val provider = RecordingProvider { ProviderResult.Success("reported-model", json) }
            assertEquals("INVALID_OUTPUT", assertIs<ReplyDraftResult.Failure>(
                drafter(provider).draft(listOf(group)), json).code)
            assertEquals(1, provider.requests.size)
        }
    }

    @Test
    fun `requires complete distinct coverage of each group`() {
        val other = group.copy(groupId = "other")
        val duplicate = RecordingProvider { ProviderResult.Success("model",
            """{"replies":[{"group_id":"format","reason_index":0,"source_indices":[0]},{"group_id":"format","reason_index":0,"source_indices":[0]}]}""") }
        assertEquals("INVALID_OUTPUT", assertIs<ReplyDraftResult.Failure>(
            drafter(duplicate).draft(listOf(group, other))).code)
        assertEquals("INVALID_OUTPUT", assertIs<ReplyDraftResult.Failure>(
            drafter(RecordingProvider()).draft(listOf(group, other))).code)
    }

    @Test
    fun `bounds source sampling without changing source quotes`() {
        val sources = (1..200).map { ReplySource(UUID(0, it.toLong()), "Exact fan request $it") }
        val provider = RecordingProvider { ProviderResult.Success("model",
            """{"replies":[{"group_id":"format","reason_index":0,"source_indices":[2,0,1]}]}""") }
        assertIs<ReplyDraftResult.Success>(drafter(provider).draft(listOf(group.copy(sources = sources))))
        val sent = mapper.readTree(provider.requests.single().input).path("groups")[0].path("sources")
        assertEquals(3, sent.size())
        assertEquals(sources.take(3).map { it.quote }, sent.toList().map { it.path("quote").asString() })
    }

    @Test
    fun `numeric commercial dated and future reasons never reach provider`() {
        val provider = RecordingProvider()
        for (reason in listOf("We offer 12 items.", "The price is low.", "Available Friday.",
            "We will make it later.", "Delivery is tomorrow.", "The next batch covers it.")) {
            assertEquals("UNSAFE_REASON", assertIs<ReplyDraftResult.Failure>(
                drafter(provider).draft(listOf(group.copy(reasons = listOf(reason))))).code)
        }
        assertTrue(provider.requests.isEmpty())
    }

    @Test
    fun `missing configuration and exhausted process cap make no further calls`() {
        val provider = RecordingProvider()
        assertEquals("NOT_CONFIGURED", assertIs<ReplyDraftResult.Failure>(
            drafter(provider, AnalysisProperties()).draft(listOf(group))).code)
        assertEquals("RUN_LIMIT_REACHED", assertIs<ReplyDraftResult.Failure>(
            drafter(provider, AnalysisProperties("test", dailyRunCap = 0)).draft(listOf(group))).code)
        val bounded = drafter(provider)
        repeat(10) { assertIs<ReplyDraftResult.Success>(bounded.draft(listOf(group))) }
        repeat(2) {
            assertEquals("RUN_LIMIT_REACHED", assertIs<ReplyDraftResult.Failure>(bounded.draft(listOf(group))).code)
        }
        assertEquals(10, provider.requests.size)
    }

    @Test
    fun `provider failures invalid JSON and elapsed timeout have no automatic retry`() {
        val failures = listOf(
            ProviderResult.Failure(AnalysisFailure.TIMEOUT) to "TIMEOUT",
            ProviderResult.Failure(AnalysisFailure.PROVIDER_FAILURE) to "PROVIDER_FAILURE",
            ProviderResult.Success("model", "{") to "INVALID_JSON",
            ProviderResult.Success("model", "x".repeat(12001)) to "INVALID_OUTPUT",
        )
        for ((response, code) in failures) {
            val provider = RecordingProvider { response }
            assertEquals(code, assertIs<ReplyDraftResult.Failure>(drafter(provider).draft(listOf(group))).code)
            assertEquals(1, provider.requests.size)
        }
        val clock = AtomicLong()
        val provider = RecordingProvider { clock.set(Duration.ofSeconds(15).toNanos()); valid() }
        assertEquals("TIMEOUT", assertIs<ReplyDraftResult.Failure>(ValidatedReplyDrafter(
            AnalysisProperties("test"), provider, mapper, clock::get,
        ).draft(listOf(group))).code)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun `oversized and invalid inputs are rejected before provider use`() {
        val provider = RecordingProvider()
        val drafter = drafter(provider)
        val invalid = listOf(emptyList(), listOf(group, group), (0..8).map { group.copy(groupId = "g$it") },
            listOf(group.copy(sources = emptyList())),
            listOf(group.copy(sources = listOf(ReplySource(UUID(0, 1), "x".repeat(501))))))
        for (input in invalid) {
            assertEquals("INVALID_INPUT", assertIs<ReplyDraftResult.Failure>(drafter.draft(input)).code)
        }
        val large = (0..7).map { number -> group.copy(groupId = "g$number", sources = (0..2).map {
            ReplySource(UUID(number.toLong(), it.toLong()), "\\".repeat(500))
        }) }
        assertEquals("INPUT_LIMIT_REACHED", assertIs<ReplyDraftResult.Failure>(drafter.draft(large)).code)
        assertTrue(provider.requests.isEmpty())
    }

    private fun drafter(provider: AnalysisProvider, properties: AnalysisProperties = AnalysisProperties("test")) =
        ValidatedReplyDrafter(properties, provider, mapper)

    private fun valid() = ProviderResult.Success("reported-model",
        """{"replies":[{"group_id":"format","reason_index":0,"source_indices":[0]}]}""")

    private inner class RecordingProvider(private val response: () -> ProviderResult = { valid() }) : AnalysisProvider {
        val requests = mutableListOf<AnalysisRequest>()
        val budgets = mutableListOf<Duration>()
        override fun generate(request: AnalysisRequest, budget: Duration): ProviderResult {
            requests += request
            budgets += budget
            return response()
        }
    }
}

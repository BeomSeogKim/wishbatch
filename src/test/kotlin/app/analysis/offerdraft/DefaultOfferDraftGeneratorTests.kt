package app.analysis.offerdraft

import app.analysis.AnalysisProperties
import app.analysis.AnalysisProvider
import app.analysis.AnalysisPurpose
import app.analysis.AnalysisRequest
import app.analysis.AnalysisTransport
import app.analysis.AuxiliaryCallBudget
import app.analysis.OpenAiAnalysisProvider
import app.analysis.ProviderHttpResponse
import app.analysis.ProviderResult
import app.campaign.offerdraft.OfferDraftEvidence
import app.campaign.offerdraft.OfferDraftGeneration
import app.campaign.offerdraft.OfferDraftInput
import app.campaign.offerdraft.OfferDraftValidator
import app.campaign.port.AnalysisFailure
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class DefaultOfferDraftGeneratorTests {
    private val mapper = JsonMapper.builder().build()
    private val input = OfferDraftInput(listOf("Art print", "Soft paper"),
        listOf(OfferDraftEvidence(UUID.randomUUID(), "I would like a print.")), "a".repeat(64))
    private val timeout = Duration.ofSeconds(15)
    private val json = """{"title":"Art print","description":"Soft paper."}"""

    @Test
    fun `adapter sends exactly one dedicated purpose request with strict schema and untouched bounded sources`() {
        val calls = mutableListOf<Pair<AnalysisRequest, Duration>>()
        val adapter = DefaultOfferDraftGenerator(AnalysisProvider { request, budget ->
            calls += request to budget
            ProviderResult.Success("offline-model", json)
        }, mapper)
        assertEquals(OfferDraftGeneration.Generated(json, "offline-model"), adapter.generate(input, timeout, 1536))
        val (request, budget) = calls.single()
        assertEquals(AnalysisPurpose.OFFER_COPY, request.purpose)
        assertFalse(request.retry)
        assertEquals(1536, request.maxOutputTokens)
        assertEquals(timeout, budget)
        assertEquals(OfferDraftValidator(mapper).schema(), request.schema)
        val tree = mapper.readTree(request.input)
        assertEquals(setOf("facts", "evidence", "sourceHash"), tree.properties().map { it.key }.toSet())
        assertEquals(input.evidence.single().quote, tree["evidence"][0]["quote"].asString())
        assertEquals(input.sourceHash, tree["sourceHash"].asString())
        assertEquals("Soft paper", tree["facts"][1].asString())
    }

    @Test
    fun `provider failures return unavailable without retry`() {
        var calls = 0
        for (reason in listOf(AnalysisFailure.TIMEOUT, AnalysisFailure.PROVIDER_FAILURE, AnalysisFailure.RUN_LIMIT_REACHED)) {
            val adapter = DefaultOfferDraftGenerator(AnalysisProvider { _, _ ->
                calls++
                ProviderResult.Failure(reason)
            }, mapper)
            assertEquals(OfferDraftGeneration.Unavailable(reason.name), adapter.generate(input, timeout, 1536))
        }
        assertEquals(3, calls)
    }

    @Test
    fun `oversized evidence payloads and budgets never reach provider`() {
        var calls = 0
        val adapter = DefaultOfferDraftGenerator(AnalysisProvider { _, _ ->
            calls++
            ProviderResult.Success("offline", json)
        }, mapper)
        for (invalid in listOf(input.copy(evidence = List(11) { input.evidence.single() }),
            input.copy(evidence = emptyList()), input.copy(facts = List(31) { "Soft paper" }),
            input.copy(facts = List(30) { "p".repeat(500) }, evidence = List(10) { input.evidence.single().copy(quote = "q".repeat(500)) }))) {
            assertIs<OfferDraftGeneration.Unavailable>(adapter.generate(invalid, timeout, 1536))
        }
        assertIs<OfferDraftGeneration.Unavailable>(adapter.generate(input, timeout.plusSeconds(1), 1536))
        assertIs<OfferDraftGeneration.Unavailable>(adapter.generate(input, timeout, 2048))
        assertEquals(0, calls)
    }

    @Test
    fun `actual provider blocks cap zero and shared auxiliary pool counts failed offer transport attempts`() {
        var sends = 0
        val transport = AnalysisTransport {
            sends++
            CompletableFuture.completedFuture(ProviderHttpResponse(503, "{}"))
        }
        val pool = AuxiliaryCallBudget(Clock.fixed(Instant.parse("2026-11-12T01:00:00Z"), ZoneOffset.UTC))
        fun adapter(cap: Int) = DefaultOfferDraftGenerator(OpenAiAnalysisProvider(
            AnalysisProperties("synthetic-test-key", dailyRunCap = cap), mapper, transport, pool,
        ), mapper)
        assertEquals(OfferDraftGeneration.Unavailable("RUN_LIMIT_REACHED"), adapter(0).generate(input, timeout, 1536))
        assertEquals(0, sends)
        assertEquals(OfferDraftGeneration.Unavailable("PROVIDER_FAILURE"), adapter(2).generate(input, timeout, 1536))
        assertEquals(OfferDraftGeneration.Unavailable("PROVIDER_FAILURE"), adapter(2).generate(input, timeout, 1536))
        assertEquals(OfferDraftGeneration.Unavailable("RUN_LIMIT_REACHED"), adapter(2).generate(input, timeout, 1536))
        assertEquals(2, sends)
    }
}

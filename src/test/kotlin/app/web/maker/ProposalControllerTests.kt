package app.web.maker

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.model.AnalysisRun
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Money
import app.campaign.model.NeedAttribute
import app.campaign.model.NeedStrength
import app.campaign.model.WishKind
import app.campaign.port.AnalysisFailure
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalysisResult
import app.campaign.port.AnalysisValidationFlag
import app.campaign.port.AnalysisValidationResult
import app.campaign.port.AnalysisValidationRule
import app.campaign.port.AnalyzedWish
import app.campaign.port.MakerQuestion
import app.campaign.port.NotServedGroup
import app.campaign.port.Proposal
import app.campaign.port.ProposalEvidence
import app.campaign.port.WishNeed
import app.campaign.port.WishVerdict
import app.campaign.rules.analysisInputHash
import app.campaign.rules.optionWishCounts
import app.campaign.workspace.WorkspaceService
import app.testing.InMemoryWishAnalyzer
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(ProposalControllerTests.Configuration::class)
@Transactional
class ProposalControllerTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val workspaces: WorkspaceService,
    @Autowired private val runs: AnalysisRunService,
    @Autowired private val analyzer: InMemoryWishAnalyzer,
    @Autowired private val entityManager: EntityManager,
    @Autowired private val mapper: JsonMapper,
) {
    @TestConfiguration
    class Configuration {
        @Bean
        @Primary
        fun proposalAnalyzer() = InMemoryWishAnalyzer()
    }

    @BeforeEach
    fun resetScript() {
        analyzer.results.clear()
    }

    @Test
    fun `fresh workspace renders stored evidence and catalog without a live call`() {
        val before = analyzer.calls.size
        val workspace = workspaces.create()
        val stored = assertNotNull(runs.latest(workspace.token))
        val html = page(workspace.token)
        assertEquals(before, analyzer.calls.size)
        assertContains(html, "Sandbox demo. No real money moves.")
        assertContains(html, "Stored seed run")
        assertContains(html, stored.run.model)
        assertContains(html, "datetime=\"${stored.run.createdAt}\"")
        assertContains(html, "USD $6.50 / unit")
        assertContains(html, "3 units")
        assertContains(html, "10 units")
        assertContains(html, "2 weeks")
        assertContains(html, "Use this proposal")
        assertContains(html, "/w/${workspace.token}/maker/offer?run=${stored.run.id}")
        assertContains(html, "stated interest, not orders")
        assertContains(html, "Not a request")
        assertContains(html, "validation items dropped or flagged")
        assertEquals(1, Regex("<h1\\b").findAll(html).count())
        assertEquals(1, Regex("button button-primary").findAll(html).count())
        assertFalse(html.contains("<progress"))
        for (evidence in stored.output.proposal!!.rationale) {
            assertContains(html, "href=\"#wish-${evidence.wishId}\"")
            assertContains(html, "id=\"wish-${evidence.wishId}\"")
        }
        val counts = optionWishCounts(stored.output).single { it.optionId == stored.output.proposal!!.optionId }
        for ((group, count) in listOf("compatible" to counts.compatible, "depends" to counts.depends,
            "not-served" to counts.notServed, "not-a-request" to counts.notARequest)) {
            assertContains(html, "href=\"#$group\"><strong>$count</strong>")
            assertContains(html, "id=\"$group\"")
        }
        for (wishId in stored.output.questionsForMaker.flatMap { it.wishIds }) {
            assertContains(html, "href=\"#wish-$wishId\"")
        }
    }

    @Test
    fun `all typed failures redirect and retain the previous evidence`() {
        val token = workspaces.create().token
        val original = assertNotNull(runs.latest(token))
        val messages = mapOf(
            AnalysisFailure.NOT_CONFIGURED to "Live analysis is not configured.",
            AnalysisFailure.TIMEOUT to "The model took too long to respond.",
            AnalysisFailure.INVALID_JSON to "The model returned a response we could not read.",
            AnalysisFailure.INVALID_OUTPUT to "The model response did not pass the evidence checks.",
            AnalysisFailure.PROVIDER_FAILURE to "The model service is unavailable.",
            AnalysisFailure.RUN_LIMIT_REACHED to "The analysis limit has been reached",
        )
        for ((failure, message) in messages) {
            analyzer.results.add(AnalysisResult.Failure(failure))
            val result = mvc.post("/w/$token/maker/proposal").andExpect {
                status { is3xxRedirection() }
                redirectedUrl("/w/$token/maker/proposal")
            }.andReturn()
            val html = mvc.get("/w/$token/maker/proposal") {
                flashAttr("analysisError", result.flashMap["analysisError"]!!)
            }.andExpect { status { isOk() } }.andReturn().response.contentAsString
            assertContains(html, message)
            assertContains(html, "role=\"alert\"")
            assertContains(html, "last valid analysis is still shown")
            assertContains(html, original.run.model)
            assertContains(html, "offer?run=${original.run.id}")
            assertEquals(original.run.id, runs.latest(token)!!.run.id)
        }
    }

    @Test
    fun `no proposal gives its reason and no offer action`() {
        val token = workspaces.create().token
        val original = runs.latest(token)!!.output
        store(token, original.copy(proposal = null, noProposalReason = "Only large wall posters were requested.", notServed = emptyList()))
        val html = page(token)
        assertContains(html, "No feasible batch")
        assertContains(html, "Only large wall posters were requested.")
        assertFalse(html.contains("Use this proposal"))
        assertFalse(html.contains("href=\"/w/$token/maker/offer"))
        assertContains(html, "How every option compares.")
    }

    @Test
    fun `new wish notice and no visible wishes state are explicit`() {
        val token = workspaces.create().token
        assertContains(page(token), "<strong>0</strong> visible wishes added")
        workspaces.addWish(token, "Please include a fox.")
        assertContains(page(token), "<strong>1</strong> visible wishes added")
        assertContains(page(token), "Re-run to include their words")
        workspaces.listWishes(token).forEach { workspaces.setWishHidden(token, it.id, true) }
        val html = page(token)
        assertContains(html, "No visible wishes to analyze.")
        assertTrue(Regex("<button[^>]*id=\"analysis-submit\"[^>]*disabled").containsMatchIn(html))
        val before = analyzer.calls.size
        val result = mvc.post("/w/$token/maker/proposal").andExpect { status { is3xxRedirection() } }.andReturn()
        assertContains(result.flashMap["analysisError"].toString(), "no visible wishes")
        assertEquals(before, analyzer.calls.size)
    }

    @Test
    fun `live success posts redirects and exposes a clear waiting state`() {
        val token = workspaces.create().token
        val original = assertNotNull(runs.latest(token))
        analyzer.results.add(AnalysisResult.Success("test-live-model", AnalysisValidationResult.Accepted(original.output, emptyList()),
            "replaced-by-fake", Instant.parse("2090-01-01T00:00:00Z")))
        val result = mvc.post("/w/$token/maker/proposal").andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/w/$token/maker/proposal")
        }.andReturn()
        assertContains(result.flashMap["analysisNotice"].toString(), "Analysis complete")
        val html = page(token)
        assertContains(html, "test-live-model")
        assertContains(html, "Live analysis")
        assertContains(html, "Reading wishes and checking evidence")
        assertContains(html, "about thirty seconds")
        assertContains(html, "addEventListener('submit'")
        assertContains(html, "setAttribute('aria-busy', 'true')")
        assertContains(html, "document.getElementById('analysis-submit').disabled = true")
    }

    @Test
    fun `commercial values and counts come from catalog and verdicts rather than extracted fan numbers`() {
        val token = workspaces.create().token
        val options = workspaces.listOptions(token)
        val option = options.single { it.format == "postcard-set" }
        val wish = workspaces.addWish(token, "<b>I want cards.</b>", "<i>Fan</i>")
        val output = AnalysisOutput(
            wishes = listOf(AnalyzedWish(wish.id, WishKind.REQUEST,
                listOf(
                    WishNeed(NeedStrength.HARD, NeedAttribute.FORMAT, "Cards", "I want cards."),
                    WishNeed(NeedStrength.PREFERENCE, NeedAttribute.SUBJECT, "Cards", "I want cards."),
                    WishNeed(NeedStrength.UNKNOWN, NeedAttribute.OTHER, "Unspecified designs", "I want cards."),
                ),
                maxUnitPrice = Money(987654321, "USD"), quantity = 456789, region = "US")),
            verdicts = options.map { WishVerdict(wish.id, it.id,
                if (it.id == option.id) CompatibilityVerdict.COMPATIBLE else CompatibilityVerdict.INCOMPATIBLE,
                if (it.id == option.id) "The requested format is available." else "Requires cards.") },
            proposal = Proposal(option.id, mapOf("finish" to "matte"),
                listOf(ProposalEvidence("A fan explicitly asks for cards.", wish.id, "I want cards."))),
            noProposalReason = null,
            notServed = emptyList(),
            questionsForMaker = listOf(MakerQuestion("Which designs will you include?", listOf(wish.id))),
            alternatives = emptyList(),
            optionIds = options.map { it.id },
        )
        store(token, output, listOf(AnalysisValidationFlag(AnalysisValidationRule.V3, "price", "Commercial term discarded")))
        val html = page(token)
        assertContains(html, "USD $6.50 / unit")
        assertContains(html, "3 units")
        assertContains(html, "10 units")
        assertFalse(html.contains("987654321"))
        assertFalse(html.contains("9,876,543.21"))
        assertFalse(html.contains("456789"))
        assertContains(html, "&lt;b&gt;I want cards.&lt;/b&gt;")
        assertContains(html, "&lt;i&gt;Fan&lt;/i&gt;")
        assertContains(html, "Hard condition")
        assertContains(html, "Preference")
        assertContains(html, "Unknown")
        assertContains(html, "1 validation items dropped or flagged")
        for (counts in optionWishCounts(output)) {
            val row = Regex("<tr>\\s*<th scope=\"row\">${Regex.escape(options.single { it.id == counts.optionId }.name)}</th>(.*?)</tr>",
                RegexOption.DOT_MATCHES_ALL).find(html)!!.groupValues[1]
            assertEquals(listOf(counts.compatible, counts.depends, counts.notServed, counts.notARequest),
                Regex("<td>(\\d+)</td>").findAll(row).map { it.groupValues[1].toInt() }.toList())
        }
    }

    @Test
    fun `not served wishes are grouped by their conflict reason`() {
        val token = workspaces.create().token
        val output = runs.latest(token)!!.output
        val incompatible = output.verdicts.filter { it.optionId == output.proposal!!.optionId && it.verdict == CompatibilityVerdict.INCOMPATIBLE }
        val group = NotServedGroup("Requires another format", incompatible.map { it.wishId }, "The requested product format is not a postcard.")
        store(token, output.copy(notServed = listOf(group)))
        val html = page(token)
        assertContains(html, group.label)
        assertContains(html, group.reason)
        for (verdict in incompatible) assertContains(html, "id=\"wish-${verdict.wishId}\"")
    }

    @Test
    fun `unknown tokens use the shared not found response for reads and posts`() {
        for (token in listOf("missing", "x".repeat(43))) {
            mvc.get("/w/$token/maker/proposal").andExpect {
                status { isNotFound() }
                view { name("error/not-found") }
            }
            mvc.post("/w/$token/maker/proposal").andExpect {
                status { isNotFound() }
                view { name("error/not-found") }
            }
        }
    }

    private fun page(token: String): String = mvc.get("/w/$token/maker/proposal").andExpect {
        status { isOk() }
        view { name("maker/proposal") }
    }.andReturn().response.contentAsString

    private fun store(token: String, output: AnalysisOutput, flags: List<AnalysisValidationFlag> = emptyList()) {
        val workspace = workspaces.findByToken(token)!!
        entityManager.persist(AnalysisRun(workspace.id, "test-render-model",
            analysisInputHash(workspaces.listOptions(token), workspaces.listWishes(token)), mapper.writeValueAsString(output),
            flags.map { mapper.writeValueAsString(it) }, createdAt = Instant.parse("2090-01-01T00:00:00Z")))
        entityManager.flush()
    }
}

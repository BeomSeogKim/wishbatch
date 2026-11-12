package app.web.maker

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.followup.FollowupDraftAttempt
import app.campaign.followup.FollowupPage
import app.campaign.model.AnalysisRun
import app.campaign.port.AnalysisMetrics
import app.campaign.port.AnalysisTokenUsage
import app.campaign.replies.ReplyBatch
import app.campaign.workspace.WorkspaceService
import app.testing.InMemoryWishAnalyzer
import jakarta.persistence.EntityManager
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
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(ProposalExperienceTests.Configuration::class)
@Transactional
class ProposalExperienceTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val workspaces: WorkspaceService,
    @Autowired private val runs: AnalysisRunService,
    @Autowired private val entities: EntityManager,
    @Autowired private val analyzer: InMemoryWishAnalyzer,
    @Autowired private val mapper: JsonMapper,
) {
    @TestConfiguration
    class Configuration {
        @Bean
        @Primary
        fun experienceAnalyzer() = InMemoryWishAnalyzer()
    }

    @Test
    fun `demand is open directly after proposal with catalog reference and exact fallback`() {
        val workspace = workspaces.create()
        val stored = assertNotNull(runs.latest(workspace.token))
        val before = analyzer.calls.size
        val html = mvc.get("/w/${workspace.token}/maker/proposal").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertEquals(before, analyzer.calls.size)
        assertTrue(html.indexOf("id=\"analysis-submit\"") < html.indexOf("id=\"proposal-demand\""))
        assertContains(html.substringAfter("class=\"button-row\"").substringBefore("</div>"), "form=\"analysis-form\"")
        assertTrue(html.indexOf("id=\"proposal\"") < html.indexOf("id=\"analysis-form\""))
        assertTrue(html.indexOf("id=\"proposal\"") < html.indexOf("aria-label=\"Maker steps\""))
        val summary = html.substringAfter("aria-label=\"Wish groups for this proposal\"").substringBefore("This does not publish an offer.")
        assertFalse(summary.contains("<details"))
        assertContains(summary, "<blockquote>")
        assertContains(html, "120-second time limit")
        assertContains(html, "Catalog cost reference:")
        assertFalse(html.contains("Current offer price"))
        assertContains(html, "id=\"demand-exact-data\" open")
        assertContains(html, "Minimum units")
        assertContains(html, "id=\"demand-alternatives\"" )
        assertContains(html, "id=\"wish-workbench\"")
        assertContains(html, "Run evidence and validation flags")
        assertContains(html, "run=${stored.run.id}")
        assertEquals(3, Regex("<h3>(Questions to ask fans|Replies|English reading aids)</h3>").findAll(html).count())
        assertContains(html, "Saved reply and fan-question drafts do not carry over")
        assertFalse(html.contains("Recorded failure category"))
    }

    @Test
    fun `fan questions and maker decisions have separate accurate counts for each scenario`() {
        for (scenario in listOf("illustrator", "ceramicist", "textile")) {
            val workspace = workspaces.create(scenario)
            val stored = assertNotNull(runs.latest(workspace.token))
            val html = mvc.get("/w/${workspace.token}/maker/proposal").andExpect { status { isOk() } }
                .andReturn().response.contentAsString
            assertContains(html, "<h3>Questions to ask fans</h3>")
            assertContains(html, "<h2 id=\"maker-questions\">Decisions for you</h2>")
            assertContains(html, "<strong>${stored.output.questionsForMaker.size}</strong> maker decisions from this analysis")
            val result = mvc.get("/w/${workspace.token}/maker/questions").andExpect { status { isOk() } }.andReturn()
            val page = result.modelAndView!!.model["page"] as FollowupPage
            assertContains(result.response.contentAsString, "<strong>${page.questions.size}</strong> saved fan questions")
            assertContains(result.response.contentAsString, "<span>${page.questions.count { it.approved }}</span> approved")
        }
    }

    @Test
    fun `successful recorded run hides encountered failure category and retains partial token measurements`() {
        val workspace = workspaces.create()
        val stored = assertNotNull(runs.latest(workspace.token))
        val output = stored.originalOutput.copy(metrics = AnalysisMetrics(57.408,
            AnalysisTokenUsage(120, 80, null), usageComplete = false, failureCategory = "TIMEOUT"))
        entities.persist(AnalysisRun(workspace.id, "test-recorded-success", stored.run.inputHash,
            mapper.writeValueAsString(output), createdAt = stored.run.createdAt.plusSeconds(1)))
        entities.flush()
        val html = mvc.get("/w/${workspace.token}/maker/proposal").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertContains(html, "Live analysis · saved result from this workspace.")
        assertContains(html, "Partial returned token usage")
        assertContains(html, "<dd>120</dd>")
        assertContains(html, "<dd>80</dd>")
        assertContains(html, "57.41 seconds")
        assertFalse(html.contains("Recorded failure category"))
        assertEquals("TIMEOUT", runs.latest(workspace.token)!!.originalOutput.metrics!!.failureCategory)
    }

    @Test
    fun `failed followup attempt remains failed on repeated proposal reads`() {
        val workspace = workspaces.create()
        val stored = assertNotNull(runs.latest(workspace.token))
        val attempt = entities.find(FollowupDraftAttempt::class.java, stored.run.id)
            ?: FollowupDraftAttempt(stored.run.id, workspace.id, stored.run.createdAt).also(entities::persist)
        attempt.failure = "INVALID_OUTPUT"
        entities.flush()
        val before = analyzer.calls.size
        repeat(2) {
            val html = mvc.get("/w/${workspace.token}/maker/proposal").andExpect { status { isOk() } }
                .andReturn().response.contentAsString
            assertContains(html, "Draft attempt failed")
            assertFalse(html.contains("Not drafted"))
        }
        assertEquals(before, analyzer.calls.size)
        assertEquals("INVALID_OUTPUT", attempt.failure)
    }

    @Test
    fun `historical helper counts remain unavailable while evidence uses selected run`() {
        val workspace = workspaces.create()
        val stored = assertNotNull(runs.latest(workspace.token))
        entities.persist(AnalysisRun(workspace.id, stored.run.model, stored.run.inputHash, stored.run.resultJson,
            createdAt = stored.run.createdAt.plusSeconds(1)))
        entities.flush()
        val html = mvc.get("/w/${workspace.token}/maker/proposal") { param("run", stored.run.id.toString()) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(html, "Counts unavailable · latest analysis only")
        assertContains(html, "Counts unavailable · historical or changed evidence")
        assertContains(html, "/maker/replies?run=${stored.run.id}")
        assertContains(html, "/maker/glosses?run=${stored.run.id}")
    }

    @Test
    fun `reply helper counts are unavailable when current run replies need refreshing`() {
        val workspace = workspaces.create()
        val stored = assertNotNull(runs.latest(workspace.token))
        val batch = entities.find(ReplyBatch::class.java, stored.run.id)
            ?: ReplyBatch(stored.run.id, workspace.id, "outdated-evidence").also(entities::persist)
        batch.evidenceHash = "outdated-evidence"
        entities.flush()
        val before = analyzer.calls.size
        val html = mvc.get("/w/${workspace.token}/maker/proposal")
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(html, "Counts unavailable · historical or changed evidence")
        assertContains(html, "/maker/replies?run=${stored.run.id}")
        assertEquals(before, analyzer.calls.size)
    }

    @Test
    fun `reply helper counts are unavailable when current run source evidence changes`() {
        val workspace = workspaces.create()
        val stored = assertNotNull(runs.latest(workspace.token))
        workspaces.addWish(workspace.token, "Please add a fox postcard.")
        val before = analyzer.calls.size
        val html = mvc.get("/w/${workspace.token}/maker/proposal")
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(html, "Counts unavailable · historical or changed evidence")
        assertFalse(html.contains("0 groups · 0 saved replies · 0 approved"))
        assertContains(html, "/maker/replies?run=${stored.run.id}")
        assertEquals(before, analyzer.calls.size)
    }
}

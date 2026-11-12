package app.web.maker

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.followup.FollowupDraftAttempt
import app.campaign.model.AnalysisRun
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
        val afterHero = html.substringAfter("This does not publish an offer.").substringAfter("</section>").trimStart()
        assertTrue(afterHero.startsWith("<section id=\"proposal-demand\">"))
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
        assertEquals(3, Regex("<h3>(Follow-up questions|Replies|English reading aids)</h3>").findAll(html).count())
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
}

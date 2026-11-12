package app.web.workbench

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.gloss.GlossStatus
import app.campaign.gloss.WishGloss
import app.campaign.gloss.glossSourceHash
import app.campaign.model.CompatibilityVerdict
import app.campaign.port.SecondReading
import app.campaign.port.WishReadingComparison
import app.campaign.review.WishReviewService
import app.campaign.workbench.WishWorkbenchService
import app.campaign.workbench.WorkbenchFilter
import app.campaign.workspace.WorkspaceService
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional
import org.springframework.ui.ExtendedModelMap
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WishWorkbenchDetailTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val workspaces: WorkspaceService,
    @Autowired private val runs: AnalysisRunService,
    @Autowired private val reviews: WishReviewService,
    @Autowired private val workbenches: WishWorkbenchService,
    @Autowired private val entities: EntityManager,
    @Autowired private val mapper: JsonMapper,
) {
    @Test
    fun `detail keeps original readings beside current decision and saved reading aid`() {
        val workspace = workspaces.create()
        val stored = assertNotNull(runs.latest(workspace.token))
        val first = stored.originalOutput.verdicts.first()
        val wish = workspaces.listWishes(workspace.token).single { it.id == first.wishId }
        val kind = stored.originalOutput.wishes.single { it.wishId == wish.id }.kind
        val resultJson = mapper.writeValueAsString(stored.originalOutput.copy(secondReading = SecondReading(
            0, 1, listOf(WishReadingComparison(wish.id, first.optionId, kind, kind, first.verdict, CompatibilityVerdict.INCOMPATIBLE)),
        )))
        entities.createQuery("update AnalysisRun r set r.resultJson = :json where r.id = :id")
            .setParameter("json", resultJson).setParameter("id", stored.run.id).executeUpdate()
        entities.refresh(stored.run)
        reviews.decide(workspace.token, stored.run.id, wish.id, first.optionId, CompatibilityVerdict.COMPATIBLE, "Checked the full source")
        entities.persist(WishGloss(workspace.id, wish.id, glossSourceHash(wish.text), status = GlossStatus.TRANSLATED,
            inferredLanguage = "Korean", englishGloss = "English <script>unsafeMarker()</script>", model = "fixture", generatedAt = Instant.EPOCH))
        val html = mvc.get("/w/${workspace.token}/maker/proposal/wishes/${wish.id}") {
            param("run", stored.run.id.toString())
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        for (text in listOf("First reading", "Second reading", "INCOMPATIBLE", "English reading aid", "Machine translation",
            "Korean", "Override history", "Checked the full source", "A separate reason was not retained")) assertContains(html, text)
        assertContains(html, "English &lt;script&gt;unsafeMarker()&lt;/script&gt;")
        assertFalse(html.contains("<script>unsafeMarker()"))
    }

    @Test
    fun `unavailable readings are honest and detail is workspace scoped`() {
        val workspace = workspaces.create()
        val wish = workspaces.addWish(workspace.token, "  Exact  source <img src=x>  ")
        val html = mvc.get("/w/${workspace.token}/maker/proposal/wishes/${wish.id}")
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(html, "Exact  source &lt;img src=x&gt;")
        assertContains(html, "First reading unavailable")
        assertContains(html, "Second reading unavailable")
        assertContains(html, "Reading aid unavailable")
        assertContains(html, "No review history available")
        val foreign = workspaces.create()
        mvc.get("/w/${foreign.token}/maker/proposal/wishes/${wish.id}").andExpect { status { isNotFound() } }
        mvc.get("/w/${workspace.token}/maker/proposal/wishes/${UUID.randomUUID()}").andExpect { status { isNotFound() } }
    }

    @Test
    fun `fallback defaults to flagged rows but preserves explicit id sort and empty scope`() {
        val workspace = workspaces.create()
        val workbench = workbenches.read(workspace.token, null)
        val flaggedId = workbench.rows.maxBy { it.id }.id
        val flagged = workbench.copy(rows = workbench.rows.map { if (it.id == flaggedId) it.copy(flags = listOf("Review this wish")) else it })
        val model = ExtendedModelMap()
        addWorkbenchAttributes(model, flagged)
        assertEquals("flags", (model["workbenchFilter"] as WorkbenchFilter).sort)
        assertEquals(flaggedId, (model["workbenchRows"] as List<*>).first().let { (it as app.campaign.workbench.WorkbenchRow).id })
        addWorkbenchAttributes(model, flagged, WorkbenchFilter(sort = "id", q = "no matching synthetic source"), explicitSort = true)
        assertEquals("id", (model["workbenchFilter"] as WorkbenchFilter).sort)
        assertTrue((model["visibleIds"] as Set<*>).isEmpty())
        val html = mvc.get("/w/${workspace.token}/maker/proposal/wishes")
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(html, "role=\"region\" aria-label=\"Wish evidence table")
        assertContains(html, "class=\"workbench-source-link\"")
        assertContains(html, "scope=\"col\"")
    }
}

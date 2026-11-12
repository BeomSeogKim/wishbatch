package app.web.workbench

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.model.AnalysisRun
import app.campaign.workspace.WorkspaceService
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WishWorkbenchProposalTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val workspaces: WorkspaceService,
    @Autowired private val runs: AnalysisRunService,
    @Autowired private val entityManager: EntityManager,
) {
    @Test
    fun `selected proposal run owns inline workbench actions while source evidence anchors remain usable`() {
        val workspace = workspaces.create()
        val selected = assertNotNull(runs.latest(workspace.token))
        val newer = AnalysisRun(
            workspaceId = workspace.id,
            model = "synthetic-newer-reference",
            inputHash = selected.run.inputHash,
            resultJson = selected.run.resultJson,
            createdAt = selected.run.createdAt.plusSeconds(1),
        )
        entityManager.persist(newer)
        val html = mvc.get("/w/${workspace.token}/maker/proposal") {
            param("run", selected.run.id.toString())
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertEquals(1, Regex("<section[^>]*data-wish-workbench").findAll(html).count())
        assertContains(html, "data-workbench-table")
        assertContains(html, "data-workbench-bulk")
        assertContains(html, "value=\"${selected.run.id}\"")
        assertFalse(html.contains("value=\"${newer.id}\""))
        assertContains(html, "/w/${workspace.token}/maker/offer?run=${selected.run.id}")
        assertEquals(1, Regex("<h1\\b").findAll(html).count())
        assertEquals(workspaces.listWishes(workspace.token).size, Regex("data-wish-id=").findAll(html).count())
        for (evidence in selected.output.proposal!!.rationale) {
            assertContains(html, "href=\"#wish-${evidence.wishId}\"")
            assertContains(html, "id=\"wish-${evidence.wishId}\"")
        }
        assertContains(html, "name=\"_csrf\"")
        assertContains(html, "/js/wish-workbench.js")
    }
}

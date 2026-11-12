package app.campaign.offerdraft

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.workspace.WorkspaceService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@SpringBootTest(properties = ["LLM_DAILY_RUN_CAP=0"])
@Import(OfflineOfferConfiguration::class)
@Transactional
class OfferDraftCapTests @Autowired constructor(
    private val drafts: OfferDraftService,
    private val workspaces: WorkspaceService,
    private val runs: AnalysisRunService,
    private val generator: OfflineOfferGenerator,
) {
    @Test
    fun `cap zero disables generation before any provider request or stored attempt`() {
        val workspace = workspaces.create()
        val run = runs.latest(workspace.token)!!.run
        val page = drafts.page(workspace.token, run.id)
        assertFalse(page.canGenerate)
        assertEquals(OfferDraftGeneration.Unavailable("CALL_LIMIT"), drafts.generate(workspace.token, run.id, page.sourceHash))
        assertEquals(0, generator.calls.size)
        assertNull(drafts.page(workspace.token, run.id).draft)
    }
}

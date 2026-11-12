package app.web.nextbatch

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.workspace.WorkspaceService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertContains
import kotlin.test.assertNotNull

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NextBatchControllerTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val workspaces: WorkspaceService,
    @Autowired private val runs: AnalysisRunService,
) {
    @Test
    fun `render scoped stored run and reject foreign runs`() {
        val first = workspaces.create()
        val second = workspaces.create()
        val run = assertNotNull(runs.latest(first.token))
        val html = mvc.get("/w/${first.token}/maker/next-batch?run=${run.run.id}").andExpect {
            status { isOk() }
            view { name("nextbatch/page") }
        }.andReturn().response.contentAsString
        assertContains(html, "Possible following batch")
        assertContains(html, "not a promise of another batch")
        mvc.get("/w/${second.token}/maker/next-batch?run=${run.run.id}").andExpect { status { isNotFound() } }
        mvc.get("/w/invalid/maker/next-batch").andExpect { status { isNotFound() } }
    }
}

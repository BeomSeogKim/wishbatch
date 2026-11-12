package app.web.gloss

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.gloss.GlossFailure
import app.campaign.gloss.GlossResult
import app.campaign.gloss.WishGlossService
import app.campaign.gloss.WishGlossTranslator
import app.campaign.workspace.WorkspaceService
import app.web.security.securePost
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(properties = ["LLM_DAILY_RUN_CAP=100", "LLM_API_KEY="])
@AutoConfigureMockMvc
@Transactional
class WishGlossUnavailableTests @Autowired constructor(
    private val glosses: WishGlossService,
    private val workspaces: WorkspaceService,
    private val runs: AnalysisRunService,
    private val translators: ObjectProvider<WishGlossTranslator>,
    private val mvc: MockMvc,
) {
    @Test
    fun `configured production translator preserves source without credentials on failed POST`() {
        assertNotNull(translators.ifAvailable)
        val workspace = workspaces.create()
        val run = runs.latest(workspace.token)!!.run.id
        val before = glosses.read(workspace.token, run)
        assertTrue(before.rows.isNotEmpty())
        val wish = before.rows.first().wishId
        assertEquals(GlossResult.Failed(GlossFailure.NOT_CONFIGURED), glosses.generate(workspace.token, run, listOf(wish)))
        assertTrue(glosses.read(workspace.token, run).rows.all { it.saved == null })
        val path = "/w/${workspace.token}/maker/glosses"
        mvc.securePost(path) { param("run", run.toString()); param("wishIds", wish.toString()) }.andExpect {
            status { is3xxRedirection() }
            flash { attribute("glossNotice", "Translation is unavailable. Original wishes are unchanged.") }
        }
        val html = mvc.get(path) { param("run", run.toString()) }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertContains(html, "No translated reading aids yet")
        assertContains(html, "Unchecked wishes")
        assertEquals(before.rows.map { it.original }, glosses.read(workspace.token, run).rows.map { it.original })
    }
}

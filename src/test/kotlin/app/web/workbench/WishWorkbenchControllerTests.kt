package app.web.workbench

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.workspace.WorkspaceService
import app.web.security.CsrfToken
import app.web.security.securePost
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WishWorkbenchControllerTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val workspaces: WorkspaceService,
    @Autowired private val runs: AnalysisRunService,
) {
    @Test
    fun `only the pinned community scripts are added to the script policy`() {
        val workspace = workspaces.create()
        val response = mvc.get(path(workspace.token)).andExpect { status { isOk() } }.andReturn().response
        val policy = assertNotNull(response.getHeader("Content-Security-Policy"))
        val directives = policy.split(';').map(String::trim)
        val scripts = directives.single { it.startsWith("script-src ") }.split(' ').toSet()
        assertEquals(setOf("script-src", "'self'", "'unsafe-inline'", "https://*.paypal.com",
            "https://*.paypalobjects.com",
            "https://cdn.jsdelivr.net/npm/ag-grid-community@36.3.0/dist/ag-grid-community.min.noStyle.js",
            "https://cdn.jsdelivr.net/npm/ag-charts-community@14.3.0/dist/umd/ag-charts-community.min.js"), scripts)
        assertTrue(directives.filterNot { it.startsWith("script-src ") }.none { "jsdelivr" in it })
        assertFalse(response.contentAsString.contains("ag-charts-community"))
    }

    @Test
    fun `fallback renders every source wish with escaped text and a csrf protected form`() {
        val workspace = workspaces.create()
        val malicious = workspaces.addWish(workspace.token, "<script>alert('wish')</script> & <img src=x onerror=alert(1)>")
        val run = assertNotNull(runs.latest(workspace.token))
        val html = mvc.get(path(workspace.token)) { param("run", run.run.id.toString()) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertEquals(workspaces.listWishes(workspace.token).size, Regex("data-wish-id=").findAll(html).count())
        assertContains(html, "&lt;script&gt;")
        assertContains(html, "&lt;img src=x onerror=alert(1)&gt;")
        assertFalse(html.contains("<script>alert('wish')</script>"))
        assertFalse(html.contains("<img src=x"))
        assertContains(html, "name=\"_csrf\"")
        assertContains(html, "method=\"post\"")
        assertContains(html, "/maker/proposal/wishes/hide")
        assertContains(html, "Unknown")
        assertContains(html, "data-workbench-table")
        assertContains(html, "data-workbench-grid hidden")
        assertContains(html, "data-wish-id=\"${malicious.id}\"")
        assertContains(html, "value=\"${run.run.id}\"")
    }

    @Test
    fun `server filters hide unmatched rows while keeping them available for local enhancement`() {
        val workspace = workspaces.create()
        val marker = workspaces.addWish(workspace.token, "Unique needle for fallback search")
        val html = mvc.get(path(workspace.token)) { param("q", "unique NEEDLE") }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        val rows = Regex("<tr[^>]*data-wish-id=\"([^\"]+)\"[^>]*>").findAll(html).toList()
        assertEquals(workspaces.listWishes(workspace.token).size, rows.size)
        assertFalse(rows.single { it.groupValues[1] == marker.id.toString() }.value.contains("hidden"))
        assertTrue(rows.filter { it.groupValues[1] != marker.id.toString() }.all { it.value.contains("hidden") })
    }

    @Test
    fun `fallback form accepts the hidden csrf token and deduplicates selected wish ids`() {
        val workspace = workspaces.create()
        val run = assertNotNull(runs.latest(workspace.token))
        val wish = workspaces.listWishes(workspace.token).first()
        val optionId = run.output.optionIds.first()
        val page = mvc.get(path(workspace.token)).andExpect { status { isOk() } }.andReturn()
        val csrf = page.request.getAttribute("_csrf") as CsrfToken
        mvc.post("${path(workspace.token)}/hide") {
            session = page.request.session as MockHttpSession
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            content = "_csrf=${csrf.token}&run=${run.run.id}&optionId=$optionId&wishIds=${wish.id}&wishIds=${wish.id}"
        }.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("${path(workspace.token)}?run=${run.run.id}&optionId=$optionId")
        }
        assertTrue(workspaces.listWishes(workspace.token).single { it.id == wish.id }.hidden)
        assertEquals(1, workspaces.listWishes(workspace.token).count { it.hidden })
    }

    @Test
    fun `bulk validates every workspace run and option reference before hiding any wish`() {
        val workspace = workspaces.create()
        val foreign = workspaces.create()
        val run = assertNotNull(runs.latest(workspace.token))
        val foreignRun = assertNotNull(runs.latest(foreign.token))
        val own = workspaces.listWishes(workspace.token).first()
        val foreignWish = workspaces.listWishes(foreign.token).first()
        val newWish = workspaces.addWish(workspace.token, "Not in the stored run")
        val optionId = run.output.optionIds.first()
        for (invalidWish in listOf(foreignWish.id, newWish.id, UUID.randomUUID())) {
            mvc.securePost("${path(workspace.token)}/hide") {
                param("run", run.run.id.toString())
                param("optionId", optionId.toString())
                param("wishIds", own.id.toString(), invalidWish.toString())
            }.andExpect { status { isBadRequest() } }
            assertFalse(workspaces.listWishes(workspace.token).single { it.id == own.id }.hidden)
        }
        mvc.securePost("${path(workspace.token)}/hide") {
            param("run", run.run.id.toString())
            param("optionId", foreignRun.output.optionIds.first().toString())
            param("wishIds", own.id.toString())
        }.andExpect { status { isBadRequest() } }
        mvc.securePost("${path(workspace.token)}/hide") {
            param("run", foreignRun.run.id.toString())
            param("optionId", optionId.toString())
            param("wishIds", own.id.toString())
        }.andExpect { status { isNotFound() } }
        assertFalse(workspaces.listWishes(workspace.token).single { it.id == own.id }.hidden)
        assertFalse(workspaces.listWishes(foreign.token).single { it.id == foreignWish.id }.hidden)
    }

    @Test
    fun `missing csrf cannot mutate and foreign runs cannot be read`() {
        val workspace = workspaces.create()
        val foreign = workspaces.create()
        val run = assertNotNull(runs.latest(workspace.token))
        mvc.post("${path(workspace.token)}/hide") {
            param("run", run.run.id.toString())
            param("optionId", run.output.optionIds.first().toString())
            param("wishIds", workspaces.listWishes(workspace.token).first().id.toString())
        }.andExpect { status { isForbidden() } }
        mvc.get(path(foreign.token)) { param("run", run.run.id.toString()) }
            .andExpect { status { isNotFound() } }
        assertFalse(workspaces.listWishes(workspace.token).any { it.hidden })
    }

    private fun path(token: String) = "/w/$token/maker/proposal/wishes"
}

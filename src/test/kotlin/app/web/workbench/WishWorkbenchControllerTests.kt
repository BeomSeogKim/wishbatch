package app.web.workbench

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.model.AnalysisRun
import app.campaign.model.CompatibilityVerdict
import app.campaign.review.WishReviewService
import app.campaign.workspace.WorkspaceService
import app.web.security.CsrfToken
import app.web.security.securePost
import jakarta.persistence.EntityManager
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
    @Autowired private val reviews: WishReviewService,
    @Autowired private val entityManager: EntityManager,
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
        assertContains(html, "<table data-workbench-table tabindex=\"0\">")
        assertContains(html, "<caption>Wish evidence and labels.")
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
    fun `label query fallback scopes the selected run and option without scripts`() {
        val workspace = workspaces.create()
        val stored = assertNotNull(runs.latest(workspace.token))
        val verdict = stored.output.verdicts.first { it.verdict == CompatibilityVerdict.COMPATIBLE }
        val expected = stored.output.verdicts.filter {
            it.optionId == verdict.optionId && it.verdict == verdict.verdict
        }.map { it.wishId.toString() }.toSet()
        val html = mvc.get(path(workspace.token)) {
            param("run", stored.run.id.toString())
            param("optionId", verdict.optionId.toString())
            param("label", verdict.verdict.name)
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        val rows = Regex("<tr[^>]*data-wish-id=\"([^\"]+)\"[^>]*>").findAll(html).toList()
        assertEquals(expected, rows.filterNot { "hidden" in it.value }.map { it.groupValues[1] }.toSet())
        assertEquals(workspaces.listWishes(workspace.token).size, rows.size)
        assertContains(html, "value=\"${stored.run.id}\"")
        assertContains(html, "value=\"${verdict.optionId}\" selected")
        assertContains(html, "value=\"COMPATIBLE\" selected")
        assertContains(html, "Apply filters")
        assertContains(html, "data-workbench-table")
        assertContains(html, "name=\"_csrf\"")
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

    @Test
    fun `bulk review fallback records each wish once and escapes reasons without rewriting the run`() {
        val workspace = workspaces.create()
        val stored = assertNotNull(runs.latest(workspace.token))
        val originalJson = stored.run.resultJson
        val optionId = stored.output.optionIds.first()
        val selected = stored.output.wishes.take(2).map { it.wishId }
        val reason = "<script>reviewMarker()</script> & checked against the source"
        val page = mvc.get(path(workspace.token)).andExpect { status { isOk() } }.andReturn()
        val csrf = page.request.getAttribute("_csrf") as CsrfToken
        mvc.post("${path(workspace.token)}/review") {
            session = page.request.session as MockHttpSession
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            content = "_csrf=${csrf.token}&run=${stored.run.id}&optionId=$optionId&verdict=INCOMPATIBLE" +
                "&reason=${java.net.URLEncoder.encode(reason, Charsets.UTF_8)}" +
                (selected + selected.first()).joinToString("") { "&wishIds=$it" }
        }.andExpect { status { is3xxRedirection() } }
        val history = reviews.history(workspace.id, stored.run.id)
        assertEquals(2, history.size)
        assertEquals(selected.toSet(), history.map { it.wishId }.toSet())
        assertTrue(history.all { it.source == "MAKER" && it.reason == reason && it.revision == 1L })
        assertTrue(history.all { it.optionId == optionId && it.verdict == CompatibilityVerdict.INCOMPATIBLE })
        assertEquals(originalJson, entityManager.find(AnalysisRun::class.java, stored.run.id).resultJson)
        val effective = assertNotNull(runs.find(workspace.token, stored.run.id))
        assertTrue(effective.output.verdicts.filter { it.wishId in selected && it.optionId == optionId }
            .all { it.verdict == CompatibilityVerdict.INCOMPATIBLE })
        assertEquals(stored.originalOutput, effective.originalOutput)
        val html = mvc.get(path(workspace.token)) { param("run", stored.run.id.toString()) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(html, "&lt;script&gt;reviewMarker()&lt;/script&gt;")
        assertFalse(html.contains("<script>reviewMarker()"))
        assertContains(html, "MAKER")
        assertContains(html, "name=\"reason\"")
    }

    @Test
    fun `invalid review reason verdict or final selection cannot partially record decisions`() {
        val workspace = workspaces.create()
        val foreign = workspaces.create()
        val stored = assertNotNull(runs.latest(workspace.token))
        val foreignStored = assertNotNull(runs.latest(foreign.token))
        val own = stored.output.wishes.first().wishId
        val optionId = stored.output.optionIds.first()
        val newWish = workspaces.addWish(workspace.token, "Not analyzed by the stored run")
        val cases = listOf(
            Triple("COMPATIBLE", " ", listOf(own)),
            Triple("COMPATIBLE", "x".repeat(501), listOf(own)),
            Triple("CONDITIONAL", "Reviewed", listOf(own)),
            Triple("UNKNOWN", "Reviewed", listOf(own)),
            Triple("COMPATIBLE", "Reviewed", listOf(own, foreignStored.output.wishes.first().wishId)),
            Triple("COMPATIBLE", "Reviewed", listOf(own, newWish.id)),
            Triple("COMPATIBLE", "Reviewed", listOf(own, UUID.randomUUID())),
        )
        for ((verdict, reason, ids) in cases) {
            mvc.securePost("${path(workspace.token)}/review") {
                param("run", stored.run.id.toString())
                param("optionId", optionId.toString())
                param("verdict", verdict)
                param("reason", reason)
                param("wishIds", *ids.map { it.toString() }.toTypedArray())
            }.andExpect { status { isBadRequest() } }
            assertTrue(reviews.history(workspace.id, stored.run.id).isEmpty())
        }
        for ((runId, selectedOption) in listOf(
            stored.run.id to foreignStored.output.optionIds.first(),
            foreignStored.run.id to optionId,
        )) {
            val result = mvc.securePost("${path(workspace.token)}/review") {
                param("run", runId.toString())
                param("optionId", selectedOption.toString())
                param("wishIds", own.toString())
                param("verdict", "COMPATIBLE")
                param("reason", "Reviewed")
            }.andReturn()
            assertTrue(result.response.status in setOf(400, 404))
            assertTrue(reviews.history(workspace.id, stored.run.id).isEmpty())
        }
        assertTrue(reviews.history(foreign.id, foreignStored.run.id).isEmpty())
    }

    @Test
    fun `review needs csrf and proposal return remains scoped to the submitted run`() {
        val workspace = workspaces.create()
        val stored = assertNotNull(runs.latest(workspace.token))
        val wish = stored.output.wishes.first().wishId
        val optionId = stored.output.optionIds.first()
        mvc.post("${path(workspace.token)}/review") {
            param("run", stored.run.id.toString())
            param("optionId", optionId.toString())
            param("wishIds", wish.toString())
            param("verdict", "COMPATIBLE")
            param("reason", "Checked")
        }.andExpect { status { isForbidden() } }
        assertTrue(reviews.history(workspace.id, stored.run.id).isEmpty())
        mvc.securePost("${path(workspace.token)}/review") {
            param("run", stored.run.id.toString())
            param("optionId", optionId.toString())
            param("wishIds", wish.toString())
            param("verdict", "COMPATIBLE")
            param("reason", "Checked")
            param("returnTo", "proposal")
        }.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/w/${workspace.token}/maker/proposal?run=${stored.run.id}#wish-workbench")
        }
        assertEquals(1, reviews.history(workspace.id, stored.run.id).size)
    }

    private fun path(token: String) = "/w/$token/maker/proposal/wishes"
}

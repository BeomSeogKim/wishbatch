package app.web.maker

import app.campaign.workspace.WorkspaceService
import app.web.security.CsrfToken
import app.web.security.securePost
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MakerWishesBulkTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val workspaces: WorkspaceService,
) {
    @Test
    fun `bulk visibility changes preserve wishes and update the next analysis roster`() {
        val token = workspaces.create().token
        val selected = workspaces.listWishes(token).take(3).map { it.id }
        val original = workspaces.listWishes(token).associate { it.id to it.text }

        for (hidden in listOf(true, false)) {
            mvc.securePost("/w/$token/maker/wishes") {
                param("wishIds", *selected.map { it.toString() }.toTypedArray())
                param("hidden", hidden.toString())
            }.andExpect {
                status { is3xxRedirection() }
                redirectedUrl("/w/$token/maker/wishes")
            }

            val wishes = workspaces.listWishes(token)
            assertEquals(original, wishes.associate { it.id to it.text })
            assertTrue(wishes.filter { it.id in selected }.all { it.hidden == hidden })
            assertTrue(wishes.filter { it.id !in selected }.none { it.hidden })
            val analysisIds = workspaces.listWishes(token, includeHidden = false).map { it.id }.toSet()
            assertEquals(if (hidden) original.keys - selected.toSet() else original.keys, analysisIds)
        }
    }

    @Test
    fun `a mixed workspace selection is rejected before any wish changes`() {
        val token = workspaces.create().token
        val otherToken = workspaces.create().token
        val local = workspaces.listWishes(token).first()
        val foreign = workspaces.listWishes(otherToken).first()

        mvc.securePost("/w/$token/maker/wishes") {
            param("wishIds", local.id.toString(), foreign.id.toString())
            param("hidden", "true")
        }.andExpect { status { isNotFound() } }

        assertFalse(workspaces.listWishes(token).single { it.id == local.id }.hidden)
        assertFalse(workspaces.listWishes(otherToken).single { it.id == foreign.id }.hidden)
    }

    @Test
    fun `empty and oversized selections return an actionable error without changing wishes`() {
        val token = workspaces.create().token
        val wish = workspaces.listWishes(token).first()

        for (selection in listOf(emptyList(), List(201) { wish.id.toString() })) {
            val result = mvc.securePost("/w/$token/maker/wishes") {
                if (selection.isNotEmpty()) param("wishIds", *selection.toTypedArray())
                param("hidden", "true")
            }.andExpect {
                status { is3xxRedirection() }
                redirectedUrl("/w/$token/maker/wishes")
            }.andReturn()

            assertNotNull(result.flashMap["wishSelectionError"])
            assertTrue(workspaces.listWishes(token).none { it.hidden })
        }
    }

    @Test
    fun `native bulk form submits with session csrf and rejects unverified posts`() {
        val token = workspaces.create().token
        val page = mvc.get("/w/$token/maker/wishes").andReturn()
        val csrf = page.request.getAttribute("_csrf") as CsrfToken
        val session = page.request.session as MockHttpSession
        val ids = workspaces.listWishes(token).take(2).map { it.id.toString() }

        mvc.post("/w/$token/maker/wishes") {
            this.session = session
            param("wishIds", *ids.toTypedArray())
            param("hidden", "true")
        }.andExpect { status { isForbidden() } }
        assertTrue(workspaces.listWishes(token).none { it.hidden })

        mvc.post("/w/$token/maker/wishes") {
            this.session = session
            param("_csrf", csrf.token)
            param("wishIds", *ids.toTypedArray())
            param("hidden", "true")
        }.andExpect { status { is3xxRedirection() } }
        assertEquals(2, workspaces.listWishes(token).count { it.hidden })
    }

    @Test
    fun `wishes render one bulk form with native selection and truthful import terms`() {
        val token = workspaces.create().token
        val wish = workspaces.addWish(token, "<em>A tiny postcard</em>", "<b>Fan</b>")
        val html = mvc.get("/w/$token/maker/wishes").andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString
        val bulkForm = assertNotNull(Regex("<form\\b[^>]*id=\"wish-selection\"[^>]*>[\\s\\S]*?</form>")
            .find(html)?.value)

        assertEquals(1, Regex("<form\\b").findAll(bulkForm).count())
        assertEquals(41, Regex("name=\"wishIds\"").findAll(bulkForm).count())
        assertContains(bulkForm, "name=\"_csrf\"")
        assertContains(bulkForm, "name=\"hidden\" value=\"true\"")
        assertContains(bulkForm, "name=\"hidden\" value=\"false\"")
        assertContains(html, "&lt;em&gt;A tiny postcard&lt;/em&gt;")
        assertContains(html, "&lt;b&gt;Fan&lt;/b&gt;")
        assertContains(html, "On-site")
        assertContains(html, "Imported")
        assertContains(html, "Received (UTC)")
        assertContains(html, "datetime=\"${wish.createdAt}\"")
        assertContains(html, "not contact permission or purchase consent")
        assertContains(html, "Wishes are not orders.")
        assertEquals(1, Regex(">Review proposal</a>").findAll(html).count())
        assertTrue(html.indexOf(">Review proposal</a>") < html.indexOf("id=\"wish-selection\""))
        assertFalse(html.contains(">${wish.id}<"))
        assertFalse(html.contains("<progress"))
    }
}

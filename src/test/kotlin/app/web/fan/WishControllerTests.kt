package app.web.fan

import app.campaign.model.WishSource
import app.campaign.workspace.SeedCatalog
import app.campaign.workspace.WorkspaceService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WishControllerTests @Autowired constructor(
    private val mvc: MockMvc,
    private val workspaces: WorkspaceService,
    private val catalog: SeedCatalog,
) {
    @MockitoBean
    private lateinit var campaigns: WishCampaignAvailability

    @Test
    fun `wish page shows the maker form and a short list without changing wishes`() {
        val token = workspaces.create().token
        val html = mvc.get("/w/$token").andExpect {
            status { isOk() }
            view { name("fan/wish") }
        }.andReturn().response.contentAsString

        assertContains(html, catalog.definition.maker.name)
        assertContains(html, catalog.definition.maker.description)
        assertContains(html, "Sandbox demo. No real money moves.")
        assertContains(html, "not an order. Nothing is charged")
        assertContains(html, "action=\"/w/$token\" method=\"post\"")
        assertContains(html, "maxlength=\"500\"")
        assertContains(html, "Display name (optional)")
        assertContains(html, "0</span> / 500 characters")
        assertContains(html, "addEventListener('input'")
        assertEquals(5, Regex("<li>").findAll(html).count())
        assertFalse(html.contains("href=\"/w/$token/offer\""))
        assertEquals(40, workspaces.listWishes(token).size)
    }

    @Test
    fun `submission persists an onsite wish and redirects to a friendly confirmation`() {
        val token = workspaces.create().token
        val response = mvc.post("/w/$token") {
            param("text", "  A moon moth postcard, please!  ")
            param("displayName", "  Fern  ")
        }.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/w/$token")
            flash { attribute("wishSubmitted", true) }
        }.andReturn()
        val saved = workspaces.listWishes(token).single { it.source == WishSource.ONSITE }
        assertEquals("A moon moth postcard, please!", saved.text)
        assertEquals("Fern", saved.displayName)
        val html = mvc.perform(MockMvcRequestBuilders.get("/w/$token").flashAttrs(response.flashMap))
            .andReturn().response.contentAsString
        assertContains(html, "Thanks for sharing your wish!")
        assertContains(html, "nothing was charged")
        assertContains(html, "A moon moth postcard, please!")
    }

    @Test
    fun `five hundred characters and an omitted name are accepted`() {
        val token = workspaces.create().token
        mvc.post("/w/$token") { param("text", "x".repeat(500)) }.andExpect {
            status { is3xxRedirection() }
            flash { attribute("wishSubmitted", true) }
        }
        val saved = workspaces.listWishes(token).single { it.source == WishSource.ONSITE }
        assertEquals(500, saved.text.length)
        assertNull(saved.displayName)
        assertContains(mvc.get("/w/$token").andReturn().response.contentAsString, "Anonymous")
    }

    @Test
    fun `invalid wish text redirects with inline errors and preserves input`() {
        val token = workspaces.create().token
        for ((text, message) in listOf(
            "" to "Write a little about what you would like.",
            " \n " to "Write a little about what you would like.",
            "x".repeat(501) to "Keep your wish to 500 characters or fewer.",
        )) {
            val response = mvc.post("/w/$token") {
                param("text", text)
                param("displayName", "Fern")
            }.andExpect {
                status { is3xxRedirection() }
                redirectedUrl("/w/$token")
                flash { attribute("textError", message) }
            }.andReturn()
            val html = mvc.perform(MockMvcRequestBuilders.get("/w/$token").flashAttrs(response.flashMap))
                .andReturn().response.contentAsString
            assertContains(html, message)
            assertContains(html, "aria-invalid=\"true\"")
            assertContains(html, "value=\"Fern\"")
            assertEquals(WishForm(text, "Fern"), response.flashMap["wishForm"])
        }
        mvc.post("/w/$token").andExpect {
            flash { attribute("textError", "Write a little about what you would like.") }
        }
        assertEquals(40, workspaces.listWishes(token).size)
    }

    @Test
    fun `overlong display name has its own inline error`() {
        val token = workspaces.create().token
        val response = mvc.post("/w/$token") {
            param("text", "A small print")
            param("displayName", "n".repeat(256))
        }.andExpect {
            status { is3xxRedirection() }
            flash { attribute("nameError", "Keep your display name to 255 characters or fewer.") }
        }.andReturn()
        val html = mvc.perform(MockMvcRequestBuilders.get("/w/$token").flashAttrs(response.flashMap))
            .andReturn().response.contentAsString
        assertContains(html, "Keep your display name to 255 characters or fewer.")
        assertContains(html, "A small print</textarea>")
        assertEquals(40, workspaces.listWishes(token).size)
    }

    @Test
    fun `workspace cap is explained inline and no extra wish is saved`() {
        val token = workspaces.create().token
        repeat(160) { workspaces.addWish(token, "A print $it") }
        val response = mvc.post("/w/$token") { param("text", "One more") }.andExpect {
            status { is3xxRedirection() }
            flash { attribute("formError", "This workspace has reached its 200-wish limit. Thanks for your interest.") }
        }.andReturn()
        val html = mvc.perform(MockMvcRequestBuilders.get("/w/$token").flashAttrs(response.flashMap))
            .andReturn().response.contentAsString
        assertContains(html, "200-wish limit")
        assertContains(html, "role=\"alert\"")
        assertContains(html, "One more</textarea>")
        assertEquals(200, workspaces.listWishes(token).size)
    }

    @Test
    fun `recent wishes exclude hidden and other workspaces and escape fan text`() {
        val token = workspaces.create().token
        val otherToken = workspaces.create().token
        workspaces.addWish(otherToken, "Private to the other workspace")
        val hidden = workspaces.addWish(token, "Hidden wish")
        workspaces.setWishHidden(token, hidden.id, true)
        workspaces.addWish(token, "<script>alert('wish')</script>", "<b>Fern</b>")
        val html = mvc.get("/w/$token").andReturn().response.contentAsString
        assertFalse(html.contains("Hidden wish"))
        assertFalse(html.contains("Private to the other workspace"))
        assertFalse(html.contains("<script>alert('wish')</script>"))
        assertContains(html, "&lt;script&gt;")
        assertContains(html, "&lt;b&gt;Fern&lt;/b&gt;")
    }

    @Test
    fun `no visible wishes gives an inviting empty state`() {
        val token = workspaces.create().token
        workspaces.listWishes(token).forEach { workspaces.setWishHidden(token, it.id, true) }
        val html = mvc.get("/w/$token").andReturn().response.contentAsString
        assertContains(html, "No wishes here yet. Yours can be the first.")
    }

    @Test
    fun `open campaign seam displays the canonical offer link`() {
        val token = workspaces.create().token
        `when`(campaigns.isOpen(token)).thenReturn(true)
        val html = mvc.get("/w/$token").andReturn().response.contentAsString
        assertContains(html, "href=\"/w/$token/offer\"")
        assertContains(html, "The maker has an offer open.")
    }

    @Test
    fun `unknown tokens return the same safe not found page for reading and submitting`() {
        for (token in listOf("missing", "x".repeat(43))) {
            val read = mvc.get("/w/$token").andExpect { status { isNotFound() } }
                .andReturn().response.contentAsString
            val submit = mvc.post("/w/$token") { param("text", "") }
                .andExpect { status { isNotFound() } }.andReturn().response.contentAsString
            assertContains(read, "Page not found")
            assertContains(read, "Sandbox demo. No real money moves.")
            assertFalse(read.contains("name=\"text\""))
            assertEquals(read, submit)
        }
    }
}

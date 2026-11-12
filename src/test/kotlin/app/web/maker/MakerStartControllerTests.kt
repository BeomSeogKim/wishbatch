package app.web.maker

import app.campaign.workspace.SeedCatalog
import app.campaign.workspace.WorkspaceService
import app.web.security.securePost
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MakerStartControllerTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val workspaces: WorkspaceService,
    @Autowired private val catalog: SeedCatalog,
    @Autowired private val entityManager: EntityManager,
) {

    @Test
    fun `demo starts isolated seeded workspaces and redirects to maker home`() {
        val tokens = (1..2).map {
            val redirect = mvc.securePost("/demo").andExpect { status { is3xxRedirection() } }
                .andReturn().response.redirectedUrl!!
            assertTrue(Regex("/w/[A-Za-z0-9_-]{43}/maker").matches(redirect))
            val token = redirect.split('/')[2]
            assertNotNull(workspaces.findByToken(token))
            assertEquals(3, workspaces.listOptions(token).size)
            assertEquals(40, workspaces.listWishes(token).size)
            mvc.get(redirect).andExpect { status { isOk() }; view { name("maker/home") } }
            token
        }
        assertNotEquals(tokens[0], tokens[1])
        assertTrue(workspaces.listWishes(tokens[0]).map { it.id }.toSet()
            .intersect(workspaces.listWishes(tokens[1]).map { it.id }.toSet()).isEmpty())
    }

    @Test
    fun `each scenario starts with a stored run and shows its maker to fans`() {
        assertEquals(listOf("illustrator", "ceramicist", "textile"), catalog.scenarios.map { it.key })
        for (scenario in catalog.scenarios) {
            val url = mvc.securePost("/demo") { param("scenario", scenario.key) }
                .andExpect { status { is3xxRedirection() } }.andReturn().response.redirectedUrl!!
            val token = url.split('/')[2]
            assertEquals(scenario.key, workspaces.scenario(token).key)
            assertEquals(scenario.definition.options.map { it.name }.toSet(),
                workspaces.listOptions(token).map { it.name }.toSet())
            assertEquals(40, workspaces.listWishes(token).size)
            mvc.get("/w/$token").andExpect {
                status { isOk() }
                model { attribute("maker", scenario.definition.maker) }
            }
            mvc.get("/w/$token/maker").andExpect { model { attributeExists("offerRun") } }
        }
        mvc.securePost("/demo") { param("scenario", "unknown") }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `maker home opens at the stored proposal and shows the fan wish link`() {
        val token = workspaces.create().token
        val html = mvc.get("/w/$token/maker").andExpect {
            status { isOk() }
            view { name("maker/home") }
        }.andReturn().response.contentAsString

        assertShell(html)
        assertRail(html, token, "proposal")
        assertContains(html, "demo-scale")
        assertContains(html, "Review proposal")
        assertEquals(1, Regex("button button-primary").findAll(html).count())
        assertTrue(Regex("<a[^>]*href=\"/w/$token\"[^>]*target=\"_blank\"[^>]*rel=\"noopener noreferrer\"")
            .containsMatchIn(html))
        assertContains(html, "Open fan wish page (new tab)")
        assertContains(html, "available after an offer is published")
        assertFalse(html.contains("href=\"/w/$token/offer\""))
    }

    @Test
    fun `option catalog renders all seeded fields with money and units`() {
        val workspace = workspaces.create()
        val html = mvc.get("/w/${workspace.token}/maker/options").andExpect {
            status { isOk() }
            view { name("maker/options") }
        }.andReturn().response.contentAsString

        assertShell(html)
        assertRail(html, workspace.token, "options")
        assertContains(html, "demo-scale")
        assertContains(html, "USD $6.50 / unit")
        assertContains(html, "USD $4.00 / unit")
        assertContains(html, "USD $2.50 / unit")
        for (option in catalog.definition.options) {
            for (text in listOf(option.name, MakerDisplay().catalogLabel(option.format), option.leadTimeText, option.shipRegion,
                "${option.minBatch} units", "${option.maxCapacity} units") + option.fixedAttributes.values +
                option.allowedVariants.values.flatten()) {
                assertContains(html, text)
            }
        }
        assertContains(html, "role=\"region\"")
        assertContains(html, "tabindex=\"0\"")
        assertContains(html, "Review wishes")
        assertContains(html, "Designs in a set")
        assertFalse(html.contains("designCount"))
        assertFalse(html.contains("postcard-set"))
    }

    @Test
    fun `wishes show raw visible intake separately from classified interest without payment progress`() {
        val token = workspaces.create().token
        val wish = workspaces.addWish(token, "<script>alert('wish')</script>", "<b>Fan</b>")
        workspaces.addWish(token, "An anonymous wish")
        val html = mvc.get("/w/$token/maker/wishes").andExpect {
            status { isOk() }
            view { name("maker/wishes") }
            model { attribute("visibleWishCount", 42) }
        }.andReturn().response.contentAsString

        assertShell(html)
        assertRail(html, token, "wishes")
        assertContains(html, "<p class=\"count-label\">Visible wishes</p>")
        assertContains(html, "<p class=\"count-value\">42</p>")
        assertContains(html, "Includes comments that may not be product requests.")
        assertFalse(html.contains("<p class=\"count-label\">Stated interest</p>"))
        assertContains(html, "Wishes are not orders.")
        assertContains(html, "On-site")
        assertContains(html, "Imported")
        assertContains(html, "Received (UTC)")
        assertContains(html, "Anonymous fan")
        assertContains(html, "datetime=\"${wish.createdAt}\"")
        val readableTime = MakerDisplay().time(wish.createdAt)
        assertContains(html, ">$readableTime</time>")
        assertContains(readableTime, " UTC")
        assertFalse(html.contains(">${wish.createdAt}</time>"))
        assertContains(html, "&lt;script&gt;")
        assertContains(html, "&lt;b&gt;Fan&lt;/b&gt;")
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("<progress"))
        assertContains(html, "<textarea")
        assertContains(html, "not contact permission or purchase consent")
        assertContains(html, "Review proposal")
    }

    @Test
    fun `bulk paste redirects and displays import results`() {
        val token = workspaces.create().token
        val result = mvc.securePost("/w/$token/maker/wishes") {
            param("comments", " First wish \n\n${"x".repeat(501)}")
        }.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/w/$token/maker/wishes")
        }.andReturn()
        val html = mvc.get("/w/$token/maker/wishes") {
            flashAttr("importResult", result.flashMap["importResult"]!!)
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(html, "Added 1; skipped 1: 1 over 500 characters")
        assertContains(html, "First wish")
        mvc.securePost("/w/missing/maker/wishes") { param("comments", "Wish") }.andExpect {
            status { isNotFound() }
            view { name("error/not-found") }
        }
    }

    @Test
    fun `posting visibility hides then unhides a wish and keeps workspace isolation`() {
        val first = workspaces.create()
        val second = workspaces.create()
        val wish = workspaces.listWishes(first.token).first()
        for ((hidden, count) in listOf(true to 39, false to 40)) {
            mvc.securePost("/w/${first.token}/maker/wishes") {
                param("wishId", wish.id.toString())
                param("hidden", hidden.toString())
            }.andExpect {
                status { is3xxRedirection() }
                redirectedUrl("/w/${first.token}/maker/wishes")
            }
            assertEquals(hidden, workspaces.listWishes(first.token).single { it.id == wish.id }.hidden)
            val html = mvc.get("/w/${first.token}/maker/wishes").andExpect {
                status { isOk() }
                model { attribute("visibleWishCount", count) }
            }.andReturn().response.contentAsString
            assertContains(html, if (hidden) "Unhide" else "Hide")
            assertEquals(40, workspaces.listWishes(second.token, includeHidden = false).size)
        }
    }

    @Test
    fun `wish from another workspace cannot be hidden`() {
        val first = workspaces.create()
        val second = workspaces.create()
        val wish = workspaces.listWishes(first.token).first()
        mvc.securePost("/w/${second.token}/maker/wishes") {
            param("wishId", wish.id.toString())
            param("hidden", "true")
        }.andExpect {
            status { isNotFound() }
            view { name("error/not-found") }
        }
        assertFalse(wish.hidden)
    }

    @Test
    fun `unknown and malformed tokens render the same shared not found page for every route`() {
        val wishId = workspaces.listWishes(workspaces.create().token).first().id
        val session = mvc.get("/").andReturn().request.session as MockHttpSession
        val pages = mutableListOf<String>()
        for (token in listOf("missing", "x".repeat(43))) {
            for (suffix in listOf("", "/options", "/wishes")) {
                pages += mvc.get("/w/$token/maker$suffix") { this.session = session }.andExpect {
                    status { isNotFound() }
                    view { name("error/not-found") }
                }.andReturn().response.contentAsString
            }
            pages += mvc.securePost("/w/$token/maker/wishes", session) {
                param("wishId", wishId.toString())
                param("hidden", "true")
            }.andExpect {
                status { isNotFound() }
                view { name("error/not-found") }
            }.andReturn().response.contentAsString
        }
        assertEquals(1, pages.toSet().size)
        assertShell(pages.first())
        assertContains(pages.first(), "This page is unavailable.")
        assertContains(pages.first(), "<title>Not found | Wishbatch</title>")
        assertContains(pages.first(), "<span>Wishbatch</span>")
        assertContains(pages.first(), "<strong>Wishbatch</strong>")
        assertFalse(pages.first().contains(wishId.toString()))
    }

    @Test
    fun `empty wish list explains how to collect interest`() {
        val workspace = workspaces.create()
        entityManager.createQuery("delete from Wish w where w.workspaceId = :workspaceId")
            .setParameter("workspaceId", workspace.id).executeUpdate()
        val html = mvc.get("/w/${workspace.token}/maker/wishes").andExpect {
            status { isOk() }
            model { attribute("visibleWishCount", 0) }
        }.andReturn().response.contentAsString
        assertContains(html, "No wishes yet.")
        assertContains(html, "Open fan wish page (new tab)")
    }

    @Test
    fun `all hidden wishes explain why none are ready for analysis`() {
        val token = workspaces.create().token
        workspaces.listWishes(token).forEach { workspaces.setWishHidden(token, it.id, true) }
        val html = mvc.get("/w/$token/maker/wishes").andExpect {
            status { isOk() }
            model { attribute("visibleWishCount", 0) }
        }.andReturn().response.contentAsString
        assertContains(html, "All wishes are hidden.")
        assertContains(html, "Unhide")
    }

    private fun assertShell(html: String) {
        assertContains(html, "Sandbox demo. No real money moves.")
        assertEquals(1, Regex("<h1\\b").findAll(html).count())
        assertEquals(1, Regex("<main\\b").findAll(html).count())
    }

    private fun assertRail(html: String, token: String, current: String) {
        for ((route, label) in listOf("options" to "Options", "wishes" to "Wishes", "proposal" to "Proposal",
            "offer" to "Offer", "campaign" to "Campaign", "result" to "Settlement")) {
            assertContains(html, "href=\"/w/$token/maker/$route\"")
            assertContains(html, ">$label</a>")
        }
        assertEquals(1, Regex("aria-current=\"step\"").findAll(html).count())
        assertTrue(Regex("<li[^>]*aria-current=\"step\"[^>]*>.*?<a[^>]*href=\"/w/$token/maker/$current\"",
            RegexOption.DOT_MATCHES_ALL).containsMatchIn(html))
    }
}

package app.web.layout

import app.campaign.model.CampaignState
import app.campaign.model.Workspace
import app.campaign.overview.OverviewAction
import app.campaign.store.WorkspaceRepository
import app.campaign.workspace.WorkspaceService
import app.web.overview.overviewActionHelp
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NavigationPresentationTests @Autowired constructor(
    private val mvc: MockMvc,
    private val workspaces: WorkspaceService,
    private val workspaceRecords: WorkspaceRepository,
) {
    @Test
    fun `maker home guide follows the displayed step across campaign states`() {
        val cases = listOf(
            Triple(null, 1, "options"),
            Triple(null, 3, "proposal"),
            Triple(CampaignState.OPEN, 5, "campaign"),
            Triple(CampaignState.AWAITING_CONFIRMATION, 5, "campaign"),
            Triple(CampaignState.SETTLING, 5, "campaign"),
            Triple(CampaignState.FUNDED, 6, "result"),
            Triple(CampaignState.NOT_FUNDED, 6, "result"),
        )
        for ((state, step, route) in cases) {
            for (minimumMet in listOf(false, true)) {
                val guide = guideFor("token", "maker", state, minimumMet, makerStep = step)
                assertEquals("/w/token/maker/$route", guide.nextUrl)
                assertTrue(guide.makerView)
            }
        }
        assertEquals("Review options", guideFor("token", "maker", null, false, makerStep = 1).next)
        assertEquals("Review proposal", guideFor("token", "maker", null, false, makerStep = 3).next)
        assertEquals("Open campaign dashboard", guideFor("token", "maker", CampaignState.OPEN, false, makerStep = 5).next)
        assertEquals("View settlement result", guideFor("token", "maker", CampaignState.FUNDED, true, makerStep = 6).next)
    }

    @Test
    fun `guide role distinguishes fan routes from maker routes`() {
        for (path in listOf("", "offer", "result", "pledges/example", "how-this-batch-was-chosen",
            "campaigns/example/how-this-batch-was-chosen")) {
            assertFalse(guideFor("token", path, CampaignState.OPEN, false).makerView)
        }
        for (path in listOf("maker", "maker/overview", "maker/options", "maker/questions", "maker/result")) {
            assertTrue(guideFor("token", path, CampaignState.OPEN, false).makerView)
        }
    }

    @Test
    fun `overview help explains each action and preserves payment cautions`() {
        val expected = listOf(
            Triple("Review payment attention", "campaign", "affected pledges and pending payment actions"),
            Triple("Review wishes and analysis", "proposal", "run analysis to prepare a proposal"),
            Triple("Review proposal", "proposal", "source wishes, and unresolved questions"),
            Triple("Review confirmation", "campaign?review=true", "authorized holds and exact offer terms"),
            Triple("Review campaign", "campaign", "Holds are not completed payments"),
            Triple("Review settlement", "campaign", "Pending payments do not count as paid"),
            Triple("Review result", "result", "each pledge's payment status"),
        )
        val messages = expected.map { (label, path, text) ->
            overviewActionHelp(OverviewAction(label, path)).also { assertContains(it, text) }
        }
        assertEquals(expected.size, messages.distinct().size)
        assertContains(overviewActionHelp(OverviewAction("Review confirmation", "campaign?review=true")),
            "Captures can still fail")
        assertContains(overviewActionHelp(OverviewAction("Review payment attention", "campaign")),
            "before proceeding with production")
    }

    @Test
    fun `seeded home agrees on proposal in introduction guide rail and primary action`() {
        val token = workspaces.create().token
        val response = mvc.get("/w/$token/maker").andExpect {
            status { isOk() }
            model { attribute("makerStep", 3) }
        }.andReturn()
        val html = response.response.contentAsString
        val guide = response.modelAndView!!.model["demoGuide"] as DemoGuide
        assertEquals("/w/$token/maker/proposal", guide.nextUrl)
        assertContains(html, "Your sample proposal is ready to review")
        assertContains(html, ">Next: Review proposal</a>")
        assertTrue(Regex("<a[^>]*class=\"button button-primary\"[^>]*href=\"/w/$token/maker/proposal\\?run=[^\"]+\"")
            .containsMatchIn(html))
        assertCurrentStep(html, token, "proposal")
        assertFalse(html.contains("Review the options to see what you can make"))
        assertContains(html, "<details class=\"demo-guide-more\">")
        assertFalse(Regex("<details[^>]*\\bopen\\b").containsMatchIn(html))
        assertContains(html, "Switch to fan view")
        assertContains(html, "Decision record JSON")
        assertShellAssets(html)

        val overview = mvc.get("/w/$token/maker/overview").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertContains(overview, "Check the proposed batch, source wishes, and unresolved questions")
        assertFalse(overview.contains("Review the current information before taking any action"))
    }

    @Test
    fun `home without analysis agrees on options in introduction guide rail and primary action`() {
        val workspace = workspaceRecords.saveAndFlush(Workspace(
            "navigation-${UUID.randomUUID().toString().replace("-", "")}",
            Instant.parse("2026-01-01T00:00:00Z"),
        ))
        val token = workspace.token
        val response = mvc.get("/w/$token/maker").andExpect {
            status { isOk() }
            model { attribute("makerStep", 1) }
        }.andReturn()
        val html = response.response.contentAsString
        val guide = response.modelAndView!!.model["demoGuide"] as DemoGuide
        assertEquals("/w/$token/maker/options", guide.nextUrl)
        assertContains(html, "Review the options to see what you can make")
        assertContains(html, ">Next: Review options</a>")
        assertContains(html, "class=\"button button-primary\" href=\"/w/$token/maker/options\">Review options</a>")
        assertCurrentStep(html, token, "options")
        assertFalse(html.contains("Your sample proposal is ready to review"))
    }

    @Test
    fun `home shows maker name when the model supplies it`() {
        val token = workspaces.create().token
        val html = mvc.get("/w/$token/maker") {
            flashAttr("maker", mapOf("name" to "Sample Maker"))
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(html, "<strong>Sample Maker</strong>")
    }

    @Test
    fun `fan footer keeps testing guide while maker and public pages keep design system`() {
        val token = workspaces.create().token
        for ((path, showDesignSystem) in listOf(
            "/w/$token" to false,
            "/w/$token/maker" to true,
            "/guide" to true,
        )) {
            val html = mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString
            val footer = html.substringAfter("<footer").substringBefore("</footer>")
            assertContains(footer, "href=\"/guide\">Testing guide</a>")
            assertEquals(showDesignSystem, footer.contains("href=\"/styleguide\">Design system</a>"))
            assertShellAssets(html)
        }
    }

    private fun assertCurrentStep(html: String, token: String, route: String) {
        assertEquals(1, Regex("aria-current=\"step\"").findAll(html).count())
        assertTrue(Regex("<li[^>]*aria-current=\"step\"[^>]*>.*?<a[^>]*href=\"/w/$token/maker/$route\"",
            RegexOption.DOT_MATCHES_ALL).containsMatchIn(html))
    }

    private fun assertShellAssets(html: String) {
        assertContains(html, "href=\"/css/ux-navigation.css\"")
        assertContains(html, "src=\"/js/human-display.js\"")
    }
}

package app.web.layout

import app.campaign.model.Campaign
import app.campaign.model.CampaignState
import app.campaign.model.Offer
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.store.AnalysisRunRepository
import app.campaign.store.CampaignRepository
import app.campaign.store.OfferRepository
import app.campaign.store.PledgeRepository
import app.campaign.workspace.WorkspaceService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.servlet.ModelAndView
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DemoGuideTests @Autowired constructor(
    private val mvc: MockMvc,
    private val workspaces: WorkspaceService,
    private val analyses: AnalysisRunRepository,
    private val offers: OfferRepository,
    private val campaigns: CampaignRepository,
    private val pledges: PledgeRepository,
    private val guideConfiguration: DemoGuideConfiguration,
) {
    @Test
    fun `workspace pages show the guide without changing the existing forms`() {
        val token = workspaces.create().token
        val before = workspaces.listWishes(token).size
        val maker = mvc.get("/w/$token/maker/wishes").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertContains(maker, "Demo guide")
        assertContains(maker, "Maker: wishes")
        assertContains(maker, "href=\"/w/$token/maker/proposal\"")
        assertContains(maker, "Switch to fan view")
        assertContains(maker, "<form")
        assertContains(maker, "sandbox-notice")
        assertContains(maker, "name=\"_csrf\"")
        assertContains(maker, "name=\"_csrf_header\"")
        assertContains(maker, "href=\"/w/$token/maker/decision-record\"")
        assertContains(maker, "href=\"/w/$token/maker/decision-record.json\"")
        assertFalse(maker.contains("href=\"/w/$token/how-this-batch-was-chosen\""))
        val fan = mvc.get("/w/$token").andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(fan, "Fan: wishes")
        assertContains(fan, "href=\"/w/$token/maker\"")
        assertContains(fan, "<form")
        assertFalse(fan.contains("/maker/decision-record"))
        assertFalse(fan.contains("href=\"/w/$token/how-this-batch-was-chosen\""))
        assertEquals(before, workspaces.listWishes(token).size)
        val home = mvc.get("/").andReturn().response.contentAsString
        assertFalse(home.contains("aria-label=\"Demo guide\""))
    }

    @Test
    fun `guide reads authorized holds and the current workspace campaign`() {
        val workspace = workspaces.create()
        val option = workspaces.listOptions(workspace.token).first()
        val analysis = analyses.findByWorkspaceId(workspace.id).first()
        val offer = offers.save(Offer(workspace.id, option.id, analysis.id, "Example", "Example", emptyMap(),
            500, "USD", "Example", "US", 2, 10))
        val openedAt = Instant.parse("2030-01-01T00:00:00Z")
        val campaign = campaigns.save(Campaign(offer.id, CampaignState.OPEN, openedAt = openedAt,
            closesAt = openedAt.plusSeconds(3600), confirmDeadline = openedAt.plusSeconds(46800)))
        val path = "/w/${workspace.token}/maker/options"
        fun html() = mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString
        val initial = html()
        assertContains(initial, "authorize a sandbox pledge")
        assertContains(initial, "href=\"/w/${workspace.token}/how-this-batch-was-chosen\"")
        assertContains(initial, "href=\"/w/${workspace.token}/maker/decision-record\"")
        val fan = mvc.get("/w/${workspace.token}").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertContains(fan, "href=\"/w/${workspace.token}/how-this-batch-was-chosen\"")
        assertFalse(fan.contains("/maker/decision-record"))
        val pledge = pledges.save(Pledge(campaign.id, 2, 1000, "USD", PledgeState.CREATED, createdAt = openedAt))
        assertContains(html(), "authorize a sandbox pledge")
        pledge.state = PledgeState.AUTHORIZED
        pledges.saveAndFlush(pledge)
        assertContains(html(), "Review the holds and confirm production")
        val interceptor = guideConfiguration.guideInterceptor()
        for ((route, location, next) in listOf(
            Triple("maker/decision-record", "Maker: decision record", "/maker/campaign"),
            Triple("maker/another-page", "Maker workspace", "/maker/campaign"),
            Triple("how-this-batch-was-chosen", "Fan: batch explanation", "/offer"),
            Triple("campaigns/${campaign.id}/how-this-batch-was-chosen", "Fan: batch explanation", "/offer"),
        )) {
            val view = ModelAndView("example")
            val request = MockHttpServletRequest("GET", "/w/${workspace.token}/$route")
            interceptor.postHandle(request, MockHttpServletResponse(), Any(), view)
            val guide = view.model["demoGuide"] as DemoGuide
            assertEquals(location, guide.location)
            assertEquals("/w/${workspace.token}$next", guide.nextUrl)
        }
        val rejectedView = ModelAndView("example")
        val rejectedResponse = MockHttpServletResponse().apply { status = 404 }
        interceptor.postHandle(MockHttpServletRequest("GET", "/w/${workspace.token}/maker/missing"),
            rejectedResponse, Any(), rejectedView)
        assertFalse(rejectedView.model.containsKey("demoGuide"))
        val redirect = ModelAndView("redirect:/")
        interceptor.postHandle(MockHttpServletRequest("GET", "/w/${workspace.token}/maker/another-page"),
            MockHttpServletResponse(), Any(), redirect)
        assertFalse(redirect.model.containsKey("demoGuide"))
        val json = mvc.get("/w/${workspace.token}/maker/offer/price-check") {
            param("run", analysis.id.toString())
            param("unitPrice", "500")
        }.andExpect { status { isOk() } }.andReturn()
        assertEquals(null, json.modelAndView)
        interceptor.postHandle(MockHttpServletRequest("GET", "/w/${workspace.token}/maker/decision-record.json"),
            MockHttpServletResponse(), Any(), null)
        assertEquals(CampaignState.OPEN, campaigns.findById(campaign.id).orElseThrow().state)
        assertEquals(PledgeState.AUTHORIZED, pledges.findById(pledge.id).orElseThrow().state)
        campaign.state = CampaignState.NOT_FUNDED
        campaigns.saveAndFlush(campaign)
        val terminal = html()
        assertContains(terminal, "Review the settlement result")
        assertContains(terminal, "href=\"/w/${workspace.token}/how-this-batch-was-chosen\"")
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `published explanation pages retain current campaign guidance`() {
        val workspace = workspaces.create()
        val option = workspaces.listOptions(workspace.token).first()
        val analysis = analyses.findByWorkspaceId(workspace.id).first()
        val offer = offers.save(Offer(workspace.id, option.id, analysis.id, "Example", "Example", emptyMap(),
            500, "USD", "Example", "US", 2, 10))
        val openedAt = Instant.parse("2020-01-01T00:00:00Z")
        val campaign = campaigns.save(Campaign(offer.id, CampaignState.NOT_FUNDED, openedAt = openedAt,
            closesAt = openedAt.plusSeconds(3600), confirmDeadline = openedAt.plusSeconds(46800)))
        for (path in listOf("how-this-batch-was-chosen", "campaigns/${campaign.id}/how-this-batch-was-chosen")) {
            val html = mvc.get("/w/${workspace.token}/$path").andExpect { status { isOk() } }
                .andReturn().response.contentAsString
            assertContains(html, "Fan: batch explanation")
            assertContains(html, "Review the campaign result")
            assertContains(html, "href=\"/w/${workspace.token}/result\"")
        }
    }

    @Test
    fun `next action follows campaign state and keeps roles distinct`() {
        val token = "token"
        val base = "/w/$token"
        assertEquals("$base/maker/options", guideFor(token, "maker", null, false).nextUrl)
        assertEquals("#main", guideFor(token, "maker/proposal", null, false).nextUrl)
        assertEquals("$base/maker/wishes", guideFor(token, "maker/proposal", null, false, true).nextUrl)
        assertEquals("#main", guideFor(token, "maker/offer", null, false).nextUrl)
        assertEquals("$base/offer", guideFor(token, "maker/campaign", CampaignState.OPEN, false).nextUrl)
        assertEquals("$base/maker/campaign", guideFor(token, "maker/campaign", CampaignState.OPEN, true).nextUrl)
        assertEquals("$base/maker/campaign", guideFor(token, "maker", CampaignState.AWAITING_CONFIRMATION, true).nextUrl)
        assertEquals("$base/maker/campaign", guideFor(token, "maker", CampaignState.SETTLING, false).nextUrl)
        for (state in listOf(CampaignState.FUNDED, CampaignState.NOT_FUNDED)) {
            assertEquals("$base/maker/result", guideFor(token, "maker", state, false).nextUrl)
            assertEquals("$base/result", guideFor(token, "offer", state, false).nextUrl)
        }
        assertEquals("$base/maker/campaign", guideFor(token, "pledges/id", CampaignState.OPEN, false).nextUrl)
        assertEquals("Fan: batch explanation", guideFor(token, "how-this-batch-was-chosen", CampaignState.OPEN, false).location)
        assertEquals("Maker: decision record", guideFor(token, "maker/decision-record", CampaignState.OPEN, false).location)
    }
}

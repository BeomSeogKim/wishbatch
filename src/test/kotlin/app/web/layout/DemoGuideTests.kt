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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional
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
        val fan = mvc.get("/w/$token").andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(fan, "Fan: wishes")
        assertContains(fan, "href=\"/w/$token/maker\"")
        assertContains(fan, "<form")
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
            closesAt = openedAt.plusSeconds(3600)))
        val path = "/w/${workspace.token}/maker/options"
        fun html() = mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(html(), "authorize a sandbox pledge")
        val pledge = pledges.save(Pledge(campaign.id, 2, 1000, "USD", PledgeState.CREATED, createdAt = openedAt))
        assertContains(html(), "authorize a sandbox pledge")
        pledge.state = PledgeState.AUTHORIZED
        pledges.saveAndFlush(pledge)
        assertContains(html(), "Review the holds and confirm production")
        assertEquals(CampaignState.OPEN, campaigns.findById(campaign.id).orElseThrow().state)
        assertEquals(PledgeState.AUTHORIZED, pledges.findById(pledge.id).orElseThrow().state)
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
    }
}

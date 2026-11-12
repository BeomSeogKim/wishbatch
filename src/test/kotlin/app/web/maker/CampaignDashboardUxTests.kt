package app.web.maker

import app.campaign.model.Campaign
import app.campaign.model.Money
import app.campaign.port.AuthorizationDetails
import app.campaign.port.AuthorizationId
import app.campaign.port.PaymentResult
import app.campaign.port.PaymentStatus
import app.campaign.model.CampaignState
import app.campaign.model.Event
import app.campaign.model.Offer
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.store.AnalysisRunRepository
import app.campaign.store.CampaignRepository
import app.campaign.store.EventRepository
import app.campaign.store.OfferRepository
import app.campaign.store.PledgeRepository
import app.campaign.workspace.WorkspaceService
import app.testing.InMemoryPaymentGateway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(CampaignDashboardUxTests.Configuration::class)
class CampaignDashboardUxTests @Autowired constructor(
    private val mvc: MockMvc,
    private val workspaces: WorkspaceService,
    private val analyses: AnalysisRunRepository,
    private val offers: OfferRepository,
    private val campaigns: CampaignRepository,
    private val pledges: PledgeRepository,
    private val events: EventRepository,
    private val gateway: InMemoryPaymentGateway,
) {
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `empty open campaign leads with counts share action and inline charts before ledger`() {
        val (token, campaign) = fixture()
        val result = mvc.get("/w/$token/maker/campaign").andExpect { status { isOk() } }.andReturn()
        val html = result.response.contentAsString
        assertEquals("SHARE", result.modelAndView!!.model["nextAction"])
        assertEquals(2, Regex("<progress\\b").findAll(html).count())
        assertEquals(1, Regex("class=\"button button-primary\"").findAll(html).count())
        assertTrue(html.indexOf("aria-label=\"Campaign counts\"") < html.indexOf("id=\"campaign-charts\""))
        assertFalse(html.contains("id=\"count-history\""))
        assertContains(html, "No pledges yet.")
        assertFalse(html.contains("id=\"pledges-heading\""))
        assertContains(html, "<summary>Offer terms and exact deadlines</summary>")
        assertContains(html, "data-campaign-id=\"${campaign.id}\"")
        assertContains(html, "data-confirmation-version=")
        assertContains(html, "data-count-field=\"authorizedHolds\"")
        assertTrue(html.indexOf("ag-charts-community@14.3.0") < html.indexOf("/js/campaign-charts.js"))
        assertContains(html, "/js/live-campaign.js")
        assertContains(result.response.getHeader("Content-Security-Policy")!!, "ag-charts-community@14.3.0")
    }

    @Test
    fun `authorized minimum offers review with original confirmation acknowledgement`() {
        val (token, campaign) = fixture()
        pledges.save(Pledge(campaign.id, 3, 3000, "USD", PledgeState.AUTHORIZED, authorizationId = "synthetic-auth", createdAt = now))
        gateway.readAuthorizationResults.add(PaymentResult.Received(AuthorizationDetails(
            AuthorizationId("synthetic-auth"), Money(3000, "USD"), now.plusSeconds(86400)), PaymentStatus.CREATED))
        val result = mvc.get("/w/$token/maker/campaign?review=true").andExpect { status { isOk() } }.andReturn()
        assertEquals("CONFIRM", result.modelAndView!!.model["nextAction"])
        val html = result.response.contentAsString
        assertEquals(1, Regex("class=\"button button-primary\"").findAll(html).count())
        assertContains(html, "USD 30.00")
        assertContains(html, "name=\"acknowledged\"")
        assertContains(html, "name=\"pledgeCount\" value=\"1\"")
        assertContains(html, "A capture can fail.")
        assertContains(html, "refunded")
    }

    @Test
    fun `server reconciles settling and terminal campaigns before offering results`() {
        for ((state, action) in listOf(CampaignState.SETTLING to "RESULT", CampaignState.FUNDED to "RESULT",
            CampaignState.NOT_FUNDED to "RESULT")) {
            val (token, _) = fixture(state)
            val result = mvc.get("/w/$token/maker/campaign").andExpect { status { isOk() } }.andReturn()
            assertEquals(action, result.modelAndView!!.model["nextAction"])
            assertEquals(false, result.modelAndView!!.model["showDeadline"])
            val html = result.response.contentAsString
            assertEquals(1, Regex("class=\"button button-primary\"").findAll(html).count())
            assertContains(html, "<p hidden=\"hidden\">Time left:")
            assertFalse(html.contains("value=\"review\""))
        }
    }

    private fun fixture(state: CampaignState = CampaignState.OPEN): Pair<String, Campaign> {
        val owner = workspaces.create()
        val run = analyses.findByWorkspaceId(owner.id).single()
        val offer = offers.save(Offer(owner.id, UUID.randomUUID(), run.id, "Synthetic postcard batch",
            "Five matte A6 designs", emptyMap(), 1000, "USD", "Ships in four weeks", "US", 3, 10))
        val campaign = campaigns.save(Campaign(offer.id, state, openedAt = now,
            closesAt = now.plusSeconds(7200), confirmDeadline = now.plusSeconds(50400)))
        events.save(Event(campaign.id, "CAMPAIGN_STATE", detail = "DRAFT -> OPEN", createdAt = now))
        return owner.token to campaign
    }

    @TestConfiguration
    class Configuration {
        @Bean @Primary fun campaignUxGateway() = InMemoryPaymentGateway()
        @Bean @Primary fun campaignUxClock(): Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    }
}

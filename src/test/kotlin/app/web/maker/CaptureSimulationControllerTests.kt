package app.web.maker

import app.campaign.port.AuthorizationId
import app.campaign.port.PaymentResult
import app.campaign.port.PaymentStatus
import app.campaign.port.VoidDetails
import app.campaign.service.CampaignService
import app.campaign.store.AnalysisRunRepository
import app.campaign.store.CampaignRepository
import app.campaign.store.EventRepository
import app.campaign.store.OfferRepository
import app.campaign.store.OptionRepository
import app.campaign.store.PledgeRepository
import app.campaign.store.WishRepository
import app.campaign.store.WorkspaceRepository
import app.campaign.workspace.WorkspaceService
import app.testing.InMemoryPaymentGateway
import app.testing.PaymentCall
import app.web.security.securePost
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@SpringBootTest(properties = ["app.campaign.demo-simulation-enabled=true"])
@AutoConfigureMockMvc
@Import(MakerMoneyTestConfiguration::class)
class CaptureSimulationControllerTests @Autowired constructor(
    private val mvc: MockMvc,
    private val workspaces: WorkspaceService,
    private val analyses: AnalysisRunRepository,
    private val offers: OfferRepository,
    private val campaignRows: CampaignRepository,
    private val events: EventRepository,
    private val pledges: PledgeRepository,
    private val wishes: WishRepository,
    private val options: OptionRepository,
    private val workspaceRows: WorkspaceRepository,
    private val campaigns: CampaignService,
    private val mapper: ObjectMapper,
    private val gateway: InMemoryPaymentGateway,
    private val clock: MakerMoneyClock,
) {
    @Test
    fun `enabled simulation stays unarmed until posted and makes a capture shortfall without a capture call`() {
        val fixture = createMoneyFixture(workspaces, analyses, mapper, clock)
        try {
            val base = "/w/${fixture.workspace.token}/maker"
            mvc.securePost("$base/offer") { validFields(fixture).forEach { (key, value) -> param(key, value) } }
                .andExpect { status { is3xxRedirection() }; redirectedUrl("$base/campaign") }
            val ref = MakerCampaignLookup(offers, campaignRows).current(fixture.workspace.id)!!
            authorizeMoneyPledge(campaigns, gateway, ref, "simulation", 3, clock)
            val html = mvc.get("$base/campaign").andExpect { status { isOk() } }.andReturn().response.contentAsString
            assertContains(html, "Demo simulation")
            assertContains(html, "Simulate next capture failure")
            assertFalse(events.findByCampaignIdOrderByCreatedAtAscIdAsc(ref.campaignId).any { it.type == "SIMULATION_ARMED" })
            mvc.securePost("$base/campaign") {
                param("action", "simulate")
                param("campaignId", ref.campaignId.toString())
            }.andExpect { status { is3xxRedirection() } }
            assertEquals(1, events.findByCampaignIdOrderByCreatedAtAscIdAsc(ref.campaignId).count { it.type == "SIMULATION_ARMED" })
            gateway.voidAuthorizationResults.add(PaymentResult.Received(VoidDetails(AuthorizationId("auth-simulation")), PaymentStatus.VOIDED))
            mvc.securePost("$base/campaign") {
                mapOf("action" to "confirm", "campaignId" to ref.campaignId.toString(), "acknowledged" to "true",
                    "pledgeCount" to "1", "units" to "3", "amount" to "3000").forEach { (key, value) -> param(key, value) }
            }.andExpect { status { is3xxRedirection() }; redirectedUrl("$base/result") }
            val result = mvc.get("$base/result").andExpect { status { isOk() } }.andReturn().response.contentAsString
            assertContains(result, "Capture shortfall")
            assertContains(result, "Simulated capture failure")
            assertEquals(0, gateway.calls.count { it is PaymentCall.Capture })
        } finally {
            val workspaceOffers = offers.findByWorkspaceId(fixture.workspace.id)
            campaignRows.findByOfferIdIn(workspaceOffers.map { it.id }).forEach { campaign ->
                events.deleteAll(events.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id))
                pledges.deleteAll(pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id))
                campaignRows.delete(campaign)
            }
            offers.deleteAll(workspaceOffers)
            analyses.deleteAll(analyses.findByWorkspaceId(fixture.workspace.id))
            wishes.deleteAll(wishes.findByWorkspaceId(fixture.workspace.id))
            options.deleteAll(options.findByWorkspaceId(fixture.workspace.id))
            workspaceRows.deleteById(fixture.workspace.id)
        }
    }
}

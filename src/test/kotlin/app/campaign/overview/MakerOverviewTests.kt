package app.campaign.overview

import app.campaign.books.BooksCheckTests
import app.campaign.books.BooksGateway
import app.campaign.model.Campaign
import app.campaign.model.CampaignState
import app.campaign.model.Offer
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.model.Workspace
import app.campaign.store.CampaignRepository
import app.campaign.store.EventRepository
import app.campaign.store.OfferRepository
import app.campaign.store.PledgeRepository
import app.campaign.store.WorkspaceRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(BooksCheckTests.Fakes::class)
class MakerOverviewTests @Autowired constructor(
    private val query: MakerOverviewQuery, private val workspaces: WorkspaceRepository,
    private val campaigns: CampaignRepository, private val offers: OfferRepository,
    private val pledges: PledgeRepository, private val events: EventRepository,
    private val gateway: BooksGateway, private val mvc: MockMvc,
) {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val owned = mutableListOf<UUID>()

    @AfterEach
    fun cleanup() {
        owned.forEach { id ->
            campaigns.findByWorkspaceId(id).forEach { campaign ->
                events.deleteAll(events.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id))
                pledges.deleteAll(pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id))
                campaigns.delete(campaign)
                offers.deleteById(campaign.offerId)
            }
            workspaces.deleteById(id)
        }
        owned.clear()
    }

    @Test
    fun `empty workspace and foreign token stay isolated and unknown`() {
        val workspace = workspace()
        val view = query.read(workspace.token)
        assertNull(view.state)
        assertNull(view.interest)
        assertNull(view.authorized)
        assertEquals("proposal", view.action.path)
        val html = mvc.get("/w/${workspace.token}/maker/overview").andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(html, "Unknown (no analysis)")
        assertContains(html, "No campaign has been recorded")
        assertFalse(html.contains("maker/books?"))
        mvc.get("/w/${"x".repeat(43)}/maker/overview").andExpect { status { isNotFound() } }
    }

    @Test
    fun `expired open snapshot does not reconcile or call provider`() {
        val workspace = workspace()
        val campaign = campaign(workspace, CampaignState.OPEN)
        val pledge = pledges.saveAndFlush(Pledge(campaign.id, 3, 600, "USD", PledgeState.AUTHORIZED,
            authorizationId = "auth", createdAt = now))
        gateway.reset()
        val beforeEvents = events.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id).size
        val view = query.read(workspace.token)
        assertTrue(view.expired)
        assertEquals(3L, view.authorized)
        assertEquals(0L, view.paid)
        assertEquals("campaign", view.action.path)
        val html = mvc.get("/w/${workspace.token}/maker/overview").andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(html, "Passed; the recorded state may be stale")
        assertEquals(PledgeState.AUTHORIZED, pledges.findById(pledge.id).orElseThrow().state)
        assertEquals(CampaignState.OPEN, campaigns.findById(campaign.id).orElseThrow().state)
        assertEquals(beforeEvents, events.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id).size)
        assertTrue(gateway.calls.isEmpty())
        assertNull(query.read(workspace().token).campaignId)
        assertEquals(1, view.unavailableRecords)
        assertContains(html, "wish association and verdict are unknown")
    }

    @Test
    fun `funded shortfall and unresolved payment states require review`() {
        val workspace = workspace()
        val campaign = campaign(workspace, CampaignState.FUNDED)
        listOf(PledgeState.CAPTURE_PENDING, PledgeState.PARTIALLY_REFUNDED, PledgeState.REFUND_FAILED,
            PledgeState.VOID_FAILED, PledgeState.REFUND_PENDING).forEach {
            pledges.save(Pledge(campaign.id, 1, 200, "USD", it, createdAt = now))
        }
        val view = query.read(workspace.token)
        assertEquals(0L, view.paid)
        assertEquals("campaign", view.action.path)
        assertTrue(view.attention.any { it.contains("historical outcome") })
        listOf("capture pending", "partially refunded", "refund failed", "void failed", "refund pending").forEach { state ->
            assertTrue(view.attention.any { it.contains(state) })
        }
    }

    @Test
    fun `next action respects thresholds deadlines and historical outcomes`() {
        assertEquals("campaign?review=true", nextAction(CampaignState.OPEN, false, false, 3, 3, true).path)
        assertEquals("campaign", nextAction(CampaignState.OPEN, true, false, 3, 3, true).path)
        assertEquals("campaign?review=true", nextAction(CampaignState.AWAITING_CONFIRMATION, false, false, 3, 3, true).path)
        assertEquals("campaign", nextAction(CampaignState.AWAITING_CONFIRMATION, true, false, 3, 3, true).path)
        assertEquals("result", nextAction(CampaignState.NOT_FUNDED, false, false, 0, 3, true).path)
        assertEquals("result", nextAction(CampaignState.FUNDED, false, false, 0, 3, true).path)
    }

    private fun workspace(): Workspace = workspaces.saveAndFlush(Workspace(UUID.randomUUID().toString().replace("-", "").padEnd(43, 'x'), now)).also { owned.add(it.id) }
    private fun campaign(workspace: Workspace, state: CampaignState): Campaign {
        val offer = offers.saveAndFlush(Offer(workspace.id, UUID.randomUUID(), UUID.randomUUID(), "Cards", "Cards", emptyMap(),
            200, "USD", "Ships later", "US", 3, 10))
        return campaigns.saveAndFlush(Campaign(offer.id, state, openedAt = now.minusSeconds(1000),
            closesAt = now.minusSeconds(1), confirmDeadline = now.plusSeconds(100)))
    }
}

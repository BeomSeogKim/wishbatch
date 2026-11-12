package app.campaign.service

import app.campaign.model.AnalysisRun
import app.campaign.model.Campaign
import app.campaign.model.CampaignState
import app.campaign.model.Money
import app.campaign.model.Offer
import app.campaign.model.Option
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.model.Workspace
import app.campaign.port.AuthorizationDetails
import app.campaign.port.AuthorizationId
import app.campaign.port.CaptureDetails
import app.campaign.port.CaptureId
import app.campaign.port.PaymentResult
import app.campaign.port.PaymentStatus
import app.campaign.store.AnalysisRunRepository
import app.campaign.store.CampaignRepository
import app.campaign.store.EventRepository
import app.campaign.store.OfferRepository
import app.campaign.store.OptionRepository
import app.campaign.store.PledgeRepository
import app.campaign.store.WorkspaceRepository
import app.testing.PaymentCall
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(CampaignServiceTests.PaymentTestConfiguration::class)
class CampaignProviderTruthTests @Autowired constructor(
    private val service: CampaignService,
    private val workspaces: WorkspaceRepository,
    private val options: OptionRepository,
    private val analyses: AnalysisRunRepository,
    private val offers: OfferRepository,
    private val campaigns: CampaignRepository,
    private val pledges: PledgeRepository,
    private val events: EventRepository,
    private val gateway: CrashableGateway,
    private val clock: MutableClock,
) {
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val ownedWorkspaces = mutableListOf<UUID>()

    @BeforeEach
    fun resetGateway() {
        gateway.reset()
        clock.current = start
    }

    @AfterEach
    fun cleanFixtures() {
        ownedWorkspaces.forEach { workspaceId ->
            val workspaceOffers = offers.findByWorkspaceId(workspaceId)
            campaigns.findByOfferIdIn(workspaceOffers.map { it.id }).forEach { campaign ->
                events.deleteAll(events.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id))
                pledges.deleteAll(pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id))
                campaigns.delete(campaign)
            }
            offers.deleteAll(workspaceOffers)
            analyses.deleteAll(analyses.findByWorkspaceId(workspaceId))
            options.deleteAll(options.findByWorkspaceId(workspaceId))
            workspaces.deleteById(workspaceId)
        }
    }

    @Test
    fun `provider void removes an authorized hold before the campaign closes`() {
        for (state in listOf(CampaignState.OPEN, CampaignState.AWAITING_CONFIRMATION)) {
            val fixture = fixture(state)
            gateway.fake.readAuthorizationResults.add(PaymentResult.Received(
                authorization(fixture.pledge), PaymentStatus.VOIDED,
            ))

            val view = success(service.readCampaign(fixture.ref))

            assertEquals(state, view.state)
            assertEquals(0L, view.counts.authorizedHolds)
            assertEquals(0L, view.counts.paid)
            assertEquals(PledgeState.VOIDED, view.pledges.single().state)
        }
        assertEquals(2, gateway.fake.calls.size)
        assertTrue(gateway.fake.calls.all { it is PaymentCall.ReadAuthorization })
    }

    @Test
    fun `external full refund reduces funded paid units and remains visible without reopening campaign`() {
        val fixture = fixture(CampaignState.FUNDED)
        gateway.fake.readCaptureResults.add(PaymentResult.Received(
            capture(fixture.pledge), PaymentStatus.REFUNDED,
        ))

        val view = success(service.reconcile(fixture.ref))

        assertEquals(CampaignState.FUNDED, view.state)
        assertEquals(0L, view.counts.paid)
        assertEquals(0L, view.counts.authorizedHolds)
        assertEquals(PledgeState.REFUNDED, view.pledges.single().state)
        assertTrue(view.needsAttention.isNotEmpty())
        assertTrue(campaigns.findById(fixture.ref.campaignId).orElseThrow().needsAttention)
        assertIs<PaymentCall.ReadCapture>(gateway.fake.calls.single())
    }

    @Test
    fun `provider denial no longer counts as an authorized hold while release is unresolved`() {
        val fixture = fixture(CampaignState.OPEN)
        gateway.fake.readAuthorizationResults.add(PaymentResult.Received(
            authorization(fixture.pledge), PaymentStatus.DECLINED,
        ))
        gateway.fake.voidAuthorizationResults.add(PaymentResult.Failed(PaymentStatus.FAILED))

        val view = success(service.reconcile(fixture.ref))

        assertEquals(CampaignState.OPEN, view.state)
        assertEquals(0L, view.counts.authorizedHolds)
        assertEquals(0L, view.result.releasedUnits)
        assertEquals(PledgeState.VOID_FAILED, view.needsAttention.single().state)
        assertTrue(gateway.fake.calls.none { it is PaymentCall.Capture })
    }

    @Test
    fun `open authorization capture evidence without a capture ID never starts capture`() {
        val fixture = fixture(CampaignState.OPEN)
        gateway.fake.readAuthorizationResults.add(PaymentResult.Received(
            authorization(fixture.pledge), PaymentStatus.CAPTURED,
        ))

        val view = success(service.readCampaign(fixture.ref))

        assertEquals(CampaignState.OPEN, view.state)
        assertEquals(0L, view.counts.authorizedHolds)
        assertEquals(0L, view.counts.paid)
        assertNull(view.pledges.single().captureId)
        assertTrue(view.needsAttention.any { it.pledgeId == fixture.pledge.id })
        assertIs<PaymentCall.ReadAuthorization>(gateway.fake.calls.single())
    }

    @Test
    fun `mismatched funded capture resource stays unresolved without applying external refund`() {
        val evidenceChanges: List<(CaptureDetails) -> CaptureDetails> = listOf(
            { it.copy(amount = Money(201, "USD")) },
            { it.copy(amount = Money(200, "EUR")) },
            { it.copy(captureId = CaptureId("another-capture")) },
            { it.copy(authorizationId = AuthorizationId("another-authorization")) },
        )
        evidenceChanges.forEach { change ->
            val fixture = fixture(CampaignState.FUNDED)
            gateway.fake.readCaptureResults.add(PaymentResult.Received(
                change(capture(fixture.pledge)), PaymentStatus.REFUNDED,
            ))

            val view = success(service.reconcile(fixture.ref))

            assertEquals(CampaignState.FUNDED, view.state)
            assertEquals(fixture.pledge.captureId, view.pledges.single().captureId)
            assertNotNull(view.pledges.single().lastError)
            assertTrue(view.needsAttention.any { it.pledgeId == fixture.pledge.id })
            assertTrue(campaigns.findById(fixture.ref.campaignId).orElseThrow().needsAttention)
            assertEquals(0L, view.result.refundedUnits)
        }
        assertEquals(evidenceChanges.size, gateway.fake.calls.size)
        assertTrue(gateway.fake.calls.all { it is PaymentCall.ReadCapture })
    }

    private fun fixture(state: CampaignState): Fixture {
        val workspace = workspaces.saveAndFlush(Workspace("test-${UUID.randomUUID()}", start))
        ownedWorkspaces.add(workspace.id)
        val option = options.saveAndFlush(Option(workspace.id, "Print", "print", emptyMap(), emptyMap(),
            100, "USD", 1, 3, "Two weeks", "US"))
        val analysis = analyses.saveAndFlush(AnalysisRun(workspace.id, "synthetic", "hash", "{}", createdAt = start))
        val offer = offers.saveAndFlush(Offer(workspace.id, option.id, analysis.id, "Print", "A5 print", emptyMap(),
            200, "USD", "Shipping included", "US", 1, 3, 3))
        val campaign = campaigns.saveAndFlush(Campaign(offer.id, state,
            openedAt = start.minusSeconds(900),
            closesAt = if (state == CampaignState.AWAITING_CONFIRMATION) start.minusSeconds(1) else start.plusSeconds(900),
            confirmDeadline = start.plusSeconds(44_100)))
        val id = UUID.randomUUID()
        val pledge = pledges.saveAndFlush(Pledge(campaign.id, 1, 200, "USD",
            if (state == CampaignState.FUNDED) PledgeState.CAPTURED else PledgeState.AUTHORIZED,
            paypalOrderId = "order-$id", authorizationId = "authorization-$id",
            authorizationExpiresAt = start.plusSeconds(86_400),
            captureId = if (state == CampaignState.FUNDED) "capture-$id" else null,
            createdAt = start, id = id))
        return Fixture(CampaignRef(workspace.id, campaign.id), pledge)
    }

    private fun authorization(pledge: Pledge) = AuthorizationDetails(
        AuthorizationId(pledge.authorizationId!!), Money(pledge.amount, pledge.currency), pledge.authorizationExpiresAt,
    )

    private fun capture(pledge: Pledge) = CaptureDetails(
        CaptureId(pledge.captureId!!), Money(pledge.amount, pledge.currency), AuthorizationId(pledge.authorizationId!!),
    )

    private fun <T> success(result: ServiceResult<T>): T = assertIs<ServiceResult.Success<T>>(result).value

    private data class Fixture(val ref: CampaignRef, val pledge: Pledge)
}

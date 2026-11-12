package app.campaign.service

import app.campaign.model.Campaign
import app.campaign.model.CampaignReason
import app.campaign.model.CampaignState
import app.campaign.model.Money
import app.campaign.model.Offer
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.model.Workspace
import app.campaign.port.AuthorizationId
import app.campaign.port.AuthorizationDetails
import app.campaign.port.CaptureDetails
import app.campaign.port.CaptureId
import app.campaign.port.PaymentGateway
import app.campaign.port.PaymentResult
import app.campaign.port.PaymentStatus
import app.campaign.port.RefundDetails
import app.campaign.port.RefundId
import app.campaign.port.VoidDetails
import app.campaign.provenance.PledgeProvenanceCheckpoint
import app.campaign.provenance.DecisionRecordService
import app.campaign.rules.PaymentAction
import app.campaign.rules.idempotencyKey
import app.campaign.store.CampaignRepository
import app.campaign.store.EventRepository
import app.campaign.store.OfferRepository
import app.campaign.store.PledgeRepository
import app.campaign.store.WorkspaceRepository
import app.testing.PaymentCall
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(CampaignServiceTests.PaymentTestConfiguration::class)
class CampaignSettlementRetryTests @Autowired constructor(
    private val service: CampaignService,
    private val persistence: CampaignPersistence,
    private val workspaces: WorkspaceRepository,
    private val offers: OfferRepository,
    private val campaigns: CampaignRepository,
    private val pledges: PledgeRepository,
    private val events: EventRepository,
    private val gateway: CrashableGateway,
    private val gateways: ObjectProvider<PaymentGateway>,
    private val clock: MutableClock,
    private val provenance: PledgeProvenanceCheckpoint,
    private val records: DecisionRecordService,
) {
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val owned = mutableListOf<UUID>()

    @BeforeEach
    fun reset() {
        gateway.reset()
        clock.current = start
    }

    @AfterEach
    fun cleanup() {
        owned.forEach { workspaceId ->
            val rows = offers.findByWorkspaceId(workspaceId)
            campaigns.findByOfferIdIn(rows.map { it.id }).forEach { campaign ->
                events.deleteAll(events.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id))
                pledges.deleteAll(pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id))
                campaigns.delete(campaign)
            }
            offers.deleteAll(rows)
            workspaces.deleteById(workspaceId)
        }
    }

    @Test
    fun `failed void retries use durable exponential backoff capped at one hour`() {
        val fixture = fixture(PledgeState.AUTHORIZED)
        gateway.fake.voidAuthorizationResults.add(PaymentResult.Failed(PaymentStatus.FAILED))
        assertEquals(PledgeState.VOID_FAILED, view(fixture).needsAttention.single().state)
        val freshPayments = CampaignPayments(gateways, persistence, pledges, events, clock, provenance)
        assertFalse(freshPayments.recheckDue(fixture.pledge, PaymentAction.VOID))

        for (delay in listOf(60L, 120L, 240L, 480L, 960L, 1920L, 3600L, 3600L)) {
            val count = gateway.fake.calls.size
            clock.current = clock.current.plusSeconds(delay - 1)
            assertEquals(PledgeState.VOID_FAILED, view(fixture).needsAttention.single().state)
            assertEquals(count, gateway.fake.calls.size)
            clock.current = clock.current.plusSeconds(1)
            gateway.fake.voidAuthorizationResults.add(PaymentResult.Failed(PaymentStatus.FAILED))
            assertEquals(PledgeState.VOID_FAILED, view(fixture).needsAttention.single().state)
            assertEquals(count + 1, gateway.fake.calls.size)
        }
        clock.current = clock.current.plusSeconds(3600)
        gateway.fake.voidAuthorizationResults.add(PaymentResult.Received(
            VoidDetails(AuthorizationId(fixture.pledge.authorizationId!!)), PaymentStatus.VOIDED,
        ))
        val resolved = view(fixture)
        assertTrue(resolved.needsAttention.isEmpty())
        assertEquals(PledgeState.VOIDED, resolved.pledges.single().state)
        assertEquals(null, resolved.pledges.single().lastError)
        assertEquals(setOf(idempotencyKey(fixture.pledge.id, PaymentAction.VOID)),
            gateway.fake.calls.map { it.idempotencyKey }.toSet())
    }

    @Test
    fun `failed refund remains visible then replays its original key after the delay`() {
        val fixture = fixture(PledgeState.CAPTURED)
        gateway.fake.refundCaptureResults.add(PaymentResult.Failed(PaymentStatus.FAILED))
        val failed = view(fixture)
        assertEquals(PledgeState.REFUND_FAILED, failed.needsAttention.single().state)
        assertEquals(0L, failed.result.refundedUnits)
        assertEquals(PledgeState.REFUND_FAILED, view(fixture).pledges.single().state)
        assertEquals(1, gateway.fake.calls.size)
        clock.current = clock.current.plusSeconds(60)
        gateway.fake.readCaptureResults.add(PaymentResult.Received(capture(fixture), PaymentStatus.COMPLETED))
        gateway.fake.refundCaptureResults.add(PaymentResult.Received(refund(fixture), PaymentStatus.COMPLETED))
        val refunded = view(fixture)
        assertEquals(PledgeState.REFUNDED, refunded.pledges.single().state)
        assertTrue(refunded.needsAttention.isEmpty())
        assertEquals(1L, refunded.result.refundedUnits)
        val calls = gateway.fake.calls.filterIsInstance<PaymentCall.RefundCapture>()
        assertEquals(2, calls.size)
        assertEquals(setOf(idempotencyKey(fixture.pledge.id, PaymentAction.REFUND)), calls.map { it.idempotencyKey }.toSet())
    }

    @Test
    fun `late capture and pending refund survive read errors without counting unconfirmed money`() {
        val fixture = fixture(PledgeState.CAPTURE_PENDING)
        gateway.fake.readCaptureResults.add(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE))
        val unresolved = view(fixture)
        assertEquals(0L, unresolved.counts.paid)
        assertEquals(PledgeState.CAPTURE_PENDING, unresolved.needsAttention.single().state)
        view(fixture)
        assertEquals(1, gateway.fake.calls.size)

        clock.current = clock.current.plusSeconds(60)
        gateway.fake.readCaptureResults.add(PaymentResult.Received(capture(fixture), PaymentStatus.COMPLETED))
        gateway.fake.refundCaptureResults.add(PaymentResult.Received(refund(fixture), PaymentStatus.PENDING))
        val pendingRefund = view(fixture)
        assertEquals(CampaignState.NOT_FUNDED, pendingRefund.state)
        assertEquals(PledgeState.REFUND_PENDING, pendingRefund.needsAttention.single().state)
        assertEquals(0L, pendingRefund.counts.paid)
        assertEquals(0L, pendingRefund.result.refundedUnits)

        gateway.fake.readRefundResults.add(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE))
        assertEquals(PledgeState.REFUND_PENDING, view(fixture).pledges.single().state)
        val count = gateway.fake.calls.size
        view(fixture)
        assertEquals(count, gateway.fake.calls.size)
        clock.current = clock.current.plusSeconds(60)
        gateway.fake.readRefundResults.add(PaymentResult.Received(refund(fixture), PaymentStatus.COMPLETED))
        val settled = view(fixture)
        assertEquals(PledgeState.REFUNDED, settled.pledges.single().state)
        assertTrue(settled.needsAttention.isEmpty())
        assertEquals(1L, settled.result.refundedUnits)
        assertTrue(gateway.fake.calls.none { it is PaymentCall.Capture || it is PaymentCall.VoidAuthorization })
        assertEquals(1, gateway.fake.calls.filterIsInstance<PaymentCall.RefundCapture>().size)
    }

    @Test
    fun `failed withdrawal can be retried while the campaign remains open`() {
        val fixture = fixture(PledgeState.VOID_FAILED, CampaignState.OPEN)
        gateway.fake.voidAuthorizationResults.add(PaymentResult.Received(
            VoidDetails(AuthorizationId(fixture.pledge.authorizationId!!)), PaymentStatus.VOIDED,
        ))
        val resolved = view(fixture)
        assertEquals(CampaignState.OPEN, resolved.state)
        assertEquals(PledgeState.VOIDED, resolved.pledges.single().state)
        assertTrue(resolved.needsAttention.isEmpty())
    }

    @Test
    fun `void conflict cannot initiate a capture without a prior capture request`() {
        val fixture = fixture(PledgeState.VOID_FAILED)
        gateway.fake.voidAuthorizationResults.add(PaymentResult.Failed(PaymentStatus.ALREADY_CAPTURED))
        repeat(2) {
            gateway.fake.readAuthorizationResults.add(PaymentResult.Received(AuthorizationDetails(
                AuthorizationId(fixture.pledge.authorizationId!!), Money(200, "USD"), null,
            ), PaymentStatus.CAPTURED))
        }
        assertEquals(PledgeState.CAPTURE_PENDING, view(fixture).needsAttention.single().state)
        val unresolved = view(fixture)
        assertEquals(PledgeState.CAPTURE_PENDING, unresolved.needsAttention.single().state)
        assertEquals(0L, unresolved.counts.paid)
        assertTrue(gateway.fake.calls.none { it is PaymentCall.Capture || it is PaymentCall.RefundCapture })
    }

    @Test
    fun `confirmation cannot capture an unresolved external payment without original intent`() {
        val fixture = fixture(PledgeState.CAPTURE_PENDING, CampaignState.OPEN)
        fixture.pledge.captureId = null
        pledges.saveAndFlush(fixture.pledge)
        val approved = pledges.saveAndFlush(Pledge(fixture.ref.campaignId, 1, 200, "USD", PledgeState.AUTHORIZED,
            paypalOrderId = "approved-order", authorizationId = "approved-hold", createdAt = start.plusSeconds(1)))
        val campaign = campaigns.findById(fixture.ref.campaignId).orElseThrow()
        persistence.checkpoint { records.recordOffer(offers.findById(campaign.offerId).orElseThrow()) }
        gateway.registerOrder(approved)
        gateway.fake.readAuthorizationResults.add(PaymentResult.Received(AuthorizationDetails(
            AuthorizationId(fixture.pledge.authorizationId!!), Money(200, "USD"), null,
        ), PaymentStatus.CAPTURED))
        gateway.fake.captureResults.add(PaymentResult.Received(CaptureDetails(
            CaptureId("approved-capture"), Money(200, "USD"), AuthorizationId(approved.authorizationId!!),
        ), PaymentStatus.COMPLETED))

        val result = assertIs<ServiceResult.Success<CampaignView>>(service.confirmProduction(fixture.ref)).value

        assertEquals(CampaignState.FUNDED, result.state)
        assertEquals(1L, result.counts.paid)
        assertEquals(fixture.pledge.id, result.needsAttention.single().pledgeId)
        val capture = gateway.fake.calls.filterIsInstance<PaymentCall.Capture>().single()
        assertEquals(AuthorizationId(approved.authorizationId!!), capture.authorizationId)
        assertEquals(idempotencyKey(approved.id, PaymentAction.CAPTURE), capture.idempotencyKey)
    }

    @Test
    fun `funded partial refund is read only and never counts the full payment or a full refund`() {
        val fixture = fixture(PledgeState.CAPTURED, CampaignState.FUNDED)
        gateway.fake.readCaptureResults.add(PaymentResult.Received(capture(fixture), PaymentStatus.PARTIALLY_REFUNDED))

        val partial = view(fixture)

        assertEquals(CampaignState.FUNDED, partial.state)
        assertEquals(0L, partial.counts.paid)
        assertEquals(0L, partial.result.refundedUnits)
        assertEquals(PledgeState.PARTIALLY_REFUNDED, partial.pledges.single().state)
        assertEquals(fixture.pledge.id, partial.needsAttention.single().pledgeId)
        assertTrue(campaigns.findById(fixture.ref.campaignId).orElseThrow().needsAttention)

        for (status in listOf(PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.COMPLETED)) {
            gateway.fake.readCaptureResults.add(PaymentResult.Received(capture(fixture), status))
            val repeated = view(fixture)
            assertEquals(PledgeState.PARTIALLY_REFUNDED, repeated.pledges.single().state)
            assertEquals(0L, repeated.counts.paid)
            assertEquals(0L, repeated.result.refundedUnits)
        }
        gateway.fake.readCaptureResults.add(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE))
        val unconfirmed = view(fixture)
        assertEquals(PledgeState.PARTIALLY_REFUNDED, unconfirmed.pledges.single().state)
        assertEquals(0L, unconfirmed.counts.paid)
        assertEquals(0L, unconfirmed.result.refundedUnits)
        clock.current = clock.current.plusSeconds(60)
        gateway.fake.readCaptureResults.add(PaymentResult.Received(capture(fixture), PaymentStatus.REFUNDED))
        val refunded = view(fixture)
        assertEquals(CampaignState.FUNDED, refunded.state)
        assertEquals(PledgeState.REFUNDED, refunded.pledges.single().state)
        assertEquals(0L, refunded.counts.paid)
        assertEquals(1L, refunded.result.refundedUnits)
        assertEquals("FUNDED_PAYMENT_SHORTFALL", refunded.needsAttention.single().reason)
        view(fixture)
        assertTrue(gateway.fake.calls.all { it is PaymentCall.ReadCapture })
    }

    @Test
    fun `capture transport failure preserves a previously fully completed payment`() {
        val fixture = fixture(PledgeState.CAPTURED, CampaignState.FUNDED)
        gateway.fake.readCaptureResults.add(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE))
        val failedRead = view(fixture)
        assertEquals(PledgeState.CAPTURED, failedRead.pledges.single().state)
        assertEquals(1L, failedRead.counts.paid)
        assertEquals(fixture.pledge.id, failedRead.needsAttention.single().pledgeId)
        clock.current = clock.current.plusSeconds(60)
        gateway.fake.readCaptureResults.add(PaymentResult.Received(capture(fixture), PaymentStatus.COMPLETED))
        val confirmed = view(fixture)
        assertEquals(1L, confirmed.counts.paid)
        assertTrue(confirmed.needsAttention.isEmpty())
        assertTrue(gateway.fake.calls.all { it is PaymentCall.ReadCapture })
    }

    @Test
    fun `resource reconciliation resolves an external refund after failed or ambiguous refund without its ID`() {
        for (failure in listOf(PaymentStatus.FAILED, PaymentStatus.TRANSPORT_FAILURE)) {
            val fixture = fixture(PledgeState.CAPTURED)
            val before = gateway.fake.calls.size
            gateway.fake.refundCaptureResults.add(PaymentResult.Failed(failure))
            val failed = view(fixture)
            assertTrue(failed.pledges.single().state in setOf(PledgeState.REFUND_FAILED, PledgeState.REFUND_PENDING))
            assertEquals(null, failed.pledges.single().refundId)
            assertEquals(0L, failed.result.refundedUnits)
            view(fixture)
            assertEquals(before + 1, gateway.fake.calls.size)

            clock.current = clock.current.plusSeconds(60)
            gateway.fake.readCaptureResults.add(PaymentResult.Received(capture(fixture), PaymentStatus.REFUNDED))
            val resolved = view(fixture)
            assertEquals(PledgeState.REFUNDED, resolved.pledges.single().state)
            assertEquals(0L, resolved.counts.paid)
            assertEquals(1L, resolved.result.refundedUnits)
            assertTrue(resolved.needsAttention.isEmpty())
            view(fixture)
            view(fixture)
            val calls = gateway.fake.calls.drop(before)
            assertEquals(1, calls.filterIsInstance<PaymentCall.RefundCapture>().size)
            assertEquals(1, calls.filterIsInstance<PaymentCall.ReadCapture>().size)
            assertTrue(calls.none { it is PaymentCall.Capture || it is PaymentCall.VoidAuthorization })
        }
    }

    @Test
    fun `failed capture read cannot prove a full refund or replay the failed refund`() {
        val fixture = fixture(PledgeState.CAPTURED)
        gateway.fake.refundCaptureResults.add(PaymentResult.Failed(PaymentStatus.FAILED))
        view(fixture)
        clock.current = clock.current.plusSeconds(60)
        gateway.fake.readCaptureResults.add(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE))
        val unresolved = view(fixture)
        assertEquals(PledgeState.REFUND_FAILED, unresolved.pledges.single().state)
        assertEquals(0L, unresolved.result.refundedUnits)
        assertEquals(1, gateway.fake.calls.filterIsInstance<PaymentCall.RefundCapture>().size)
        val callsAfterFailure = gateway.fake.calls.size
        view(fixture)
        assertEquals(callsAfterFailure, gateway.fake.calls.size)

        clock.current = clock.current.plusSeconds(60)
        gateway.fake.readCaptureResults.add(PaymentResult.Received(capture(fixture), PaymentStatus.COMPLETED))
        gateway.fake.refundCaptureResults.add(PaymentResult.Received(refund(fixture), PaymentStatus.COMPLETED))
        val refunded = view(fixture)
        assertEquals(PledgeState.REFUNDED, refunded.pledges.single().state)
        assertEquals(1L, refunded.result.refundedUnits)
        val writes = gateway.fake.calls.filterIsInstance<PaymentCall.RefundCapture>()
        assertEquals(2, writes.size)
        assertEquals(setOf(idempotencyKey(fixture.pledge.id, PaymentAction.REFUND)), writes.map { it.idempotencyKey }.toSet())
    }

    @Test
    fun `not funded partial refund requires attention without guessing a remainder refund`() {
        val fixture = fixture(PledgeState.CAPTURED)
        gateway.fake.refundCaptureResults.add(PaymentResult.Failed(PaymentStatus.FAILED))
        view(fixture)
        clock.current = clock.current.plusSeconds(60)
        gateway.fake.readCaptureResults.add(PaymentResult.Received(capture(fixture), PaymentStatus.PARTIALLY_REFUNDED))
        val partial = view(fixture)
        assertEquals(PledgeState.PARTIALLY_REFUNDED, partial.pledges.single().state)
        assertEquals(0L, partial.counts.paid)
        assertEquals(0L, partial.result.refundedUnits)
        assertEquals(fixture.pledge.id, partial.needsAttention.single().pledgeId)
        gateway.fake.readCaptureResults.add(PaymentResult.Received(capture(fixture), PaymentStatus.PARTIALLY_REFUNDED))
        view(fixture)
        assertEquals(1, gateway.fake.calls.filterIsInstance<PaymentCall.RefundCapture>().size)
        assertTrue(gateway.fake.calls.none { it is PaymentCall.Capture || it is PaymentCall.VoidAuthorization })
    }

    private fun fixture(state: PledgeState, campaignState: CampaignState = CampaignState.NOT_FUNDED): Fixture {
        val workspace = workspaces.saveAndFlush(Workspace("retry-${UUID.randomUUID()}", start))
        owned.add(workspace.id)
        val offer = offers.saveAndFlush(Offer(workspace.id, UUID.randomUUID(), UUID.randomUUID(), "Print", "A5 print",
            emptyMap(), 200, "USD", "Shipping included", "US", 1, 5, 3, emptyList()))
        val campaign = campaigns.saveAndFlush(Campaign(offer.id, campaignState,
            reason = if (campaignState == CampaignState.NOT_FUNDED) CampaignReason.CAPTURE_SHORTFALL else null,
            openedAt = start, closesAt = start.plusSeconds(900), confirmDeadline = start.plusSeconds(44_100)))
        val id = UUID.randomUUID()
        val pledge = pledges.saveAndFlush(Pledge(campaign.id, 1, 200, "USD", state,
            authorizationId = "auth-$id", captureId = if (state in setOf(PledgeState.CAPTURED, PledgeState.CAPTURE_PENDING)) "cap-$id" else null,
            createdAt = start, id = id))
        return Fixture(CampaignRef(workspace.id, campaign.id), pledge)
    }

    private fun capture(fixture: Fixture) = CaptureDetails(CaptureId(fixture.pledge.captureId!!), Money(200, "USD"),
        AuthorizationId(fixture.pledge.authorizationId!!))
    private fun refund(fixture: Fixture) = RefundDetails(RefundId("refund-${fixture.pledge.id}"), Money(200, "USD"),
        CaptureId(fixture.pledge.captureId!!))
    private fun view(fixture: Fixture) = assertIs<ServiceResult.Success<CampaignView>>(service.reconcile(fixture.ref)).value
    private data class Fixture(val ref: CampaignRef, val pledge: Pledge)
}

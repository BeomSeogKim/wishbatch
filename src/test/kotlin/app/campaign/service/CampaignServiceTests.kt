package app.campaign.service

import app.campaign.model.AnalysisRun
import app.campaign.model.Campaign
import app.campaign.model.CampaignReason
import app.campaign.model.CampaignState
import app.campaign.model.Money
import app.campaign.model.Offer
import app.campaign.model.Option
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.model.ShippingAddress
import app.campaign.model.WishKind
import app.campaign.model.Workspace
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalyzedWish
import app.campaign.port.AuthorizationDetails
import app.campaign.port.AuthorizationId
import app.campaign.port.CaptureDetails
import app.campaign.port.CaptureId
import app.campaign.port.CreateAuthorizeOrder
import app.campaign.port.OrderDetails
import app.campaign.port.OrderId
import app.campaign.port.PaymentGateway
import app.campaign.port.PaymentResult
import app.campaign.port.PaymentStatus
import app.campaign.port.Proposal
import app.campaign.port.RefundDetails
import app.campaign.port.RefundId
import app.campaign.port.VoidDetails
import app.campaign.rules.CapacityDecision
import app.campaign.rules.OfferWarning
import app.campaign.rules.PaymentAction
import app.campaign.rules.idempotencyKey
import app.campaign.store.AnalysisRunRepository
import app.campaign.store.CampaignRepository
import app.campaign.store.EventRepository
import app.campaign.store.OfferRepository
import app.campaign.store.OptionRepository
import app.campaign.store.PledgeRepository
import app.campaign.store.WorkspaceRepository
import app.testing.InMemoryPaymentGateway
import app.testing.PaymentCall
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(CampaignServiceTests.PaymentTestConfiguration::class)
class CampaignServiceTests @Autowired constructor(
    private val service: CampaignService,
    private val queries: CampaignQueries,
    private val workspaces: WorkspaceRepository,
    private val options: OptionRepository,
    private val analyses: AnalysisRunRepository,
    private val offers: OfferRepository,
    private val campaigns: CampaignRepository,
    private val pledges: PledgeRepository,
    private val events: EventRepository,
    private val gateway: CrashableGateway,
    private val clock: MutableClock,
    private val mapper: ObjectMapper,
    private val persistence: CampaignPersistence,
    private val payments: CampaignPayments,
    private val tick: CampaignReconciliationTick,
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
    fun `draft enforces commercial limits and retains evidence with below cost warning`() {
        val fixture = fixture(minimum = 2, capacity = 5)
        val command = draft(fixture).copy(unitPrice = 50)
        val result = success(service.createDraft(fixture.workspace.id, command))
        assertEquals(setOf(OfferWarning.PRICE_BELOW_COST), result.warnings)
        val saved = offers.findById(result.offerId).orElseThrow()
        assertEquals(fixture.analysis.id, saved.analysisRunId)
        assertEquals(listOf(fixture.wishId), saved.supportingWishIds)
        assertEquals(CampaignState.DRAFT, campaigns.findById(result.campaignId).orElseThrow().state)

        listOf(
            command.copy(minBatch = 1), command.copy(capacity = 6),
            command.copy(unitPrice = 0), command.copy(maxQtyPerPledge = 0),
            command.copy(maxQtyPerPledge = 6), command.copy(duration = Duration.ofHours(49)),
        ).forEach { invalid ->
            assertEquals(Rejection.INVALID_OFFER, rejected(service.createDraft(fixture.workspace.id, invalid)).reason)
        }
        assertEquals(Rejection.INVALID_EVIDENCE,
            rejected(service.createDraft(fixture.workspace.id, command.copy(supportingWishIds = listOf(UUID.randomUUID())))).reason)
    }

    @Test
    fun `publishing sets deadlines allows only one active campaign and does not modify terms`() {
        val fixture = fixture(minimum = 1, capacity = 3, state = CampaignState.DRAFT)
        val published = success(service.publish(fixture.ref, Duration.ofMinutes(15)))
        assertEquals(CampaignState.OPEN, published.state)
        assertEquals(start, published.openedAt)
        assertEquals(start.plusSeconds(900), published.closesAt)
        assertEquals(start.plusSeconds(44_100), published.confirmDeadline)
        val secondDraft = success(service.createDraft(fixture.workspace.id, draft(fixture)))
        val secondRef = CampaignRef(fixture.workspace.id, secondDraft.campaignId)
        assertEquals(Rejection.ACTIVE_CAMPAIGN, rejected(service.publish(secondRef, Duration.ofHours(2))).reason)
        assertEquals(Rejection.INVALID_STATE, rejected(service.publish(fixture.ref, Duration.ofHours(48))).reason)
        assertEquals(fixture.offer.unitPrice, offers.findById(fixture.offer.id).orElseThrow().unitPrice)
        success(service.cancel(fixture.ref))
        assertEquals(CampaignState.OPEN, success(service.publish(secondRef, Duration.ofHours(2))).state)
    }

    @Test
    fun `workspace scope protects campaign pledge and evidence access`() {
        val fixture = fixture()
        val other = fixture()
        val wrongRef = CampaignRef(other.workspace.id, fixture.campaign.id)
        assertEquals(Rejection.NOT_FOUND, rejected(service.readCampaign(wrongRef)).reason)
        assertEquals(Rejection.NOT_FOUND, rejected(service.startPledge(wrongRef, 1)).reason)
        assertEquals(Rejection.NOT_FOUND,
            rejected(service.createDraft(other.workspace.id, draft(fixture))).reason)
        val pledge = authorized(fixture, 1)
        assertEquals(Rejection.NOT_FOUND,
            rejected(service.completePledge(PledgeRef(other.ref, pledge.id))).reason)
        assertTrue(gateway.fake.calls.isEmpty())
    }

    @Test
    fun `order amount and reference come from offer and unapproved orders never count`() {
        val fixture = fixture(minimum = 2, capacity = 5)
        gateway.fake.createOrderResults.add(received(OrderDetails(OrderId("order-1")), PaymentStatus.CREATED))
        val created = success(service.startPledge(fixture.ref, 2))
        assertEquals(400L, created.amount)
        assertEquals(PledgeState.CREATED, created.state)
        val call = assertIs<PaymentCall.CreateOrder>(gateway.fake.calls.single())
        assertEquals(created.id, call.request.pledgeId)
        assertEquals(Money(400, "USD"), call.request.amount)
        assertEquals(idempotencyKey(created.id, PaymentAction.CREATE_ORDER), call.idempotencyKey)
        val before = success(service.readCampaign(fixture.ref))
        assertEquals(CampaignCounts(1, 0, 0), before.counts)
        assertEquals(5L, before.capacityLeft)

        gateway.fake.authorizeOrderResults.add(received(AuthorizationDetails(
            AuthorizationId("authorization-1"), Money(400, "USD"), start.plusSeconds(86_400),
            orderId = OrderId("order-1"), payerEmail = "buyer@example.test",
        ), PaymentStatus.CREATED, "debug-authorize"))
        val approved = success(service.completePledge(PledgeRef(fixture.ref, created.id)))
        assertEquals(PledgeState.AUTHORIZED, approved.state)
        assertEquals("b***@example.test", approved.payerEmailMasked)
        healthyAuthorization(pledges.findById(created.id).orElseThrow())
        val after = success(service.readCampaign(fixture.ref))
        assertEquals(CampaignCounts(1, 2, 0), after.counts)
        assertEquals(3L, after.capacityLeft)
        assertEquals(MinimumProgress(2, 2), after.progress)
        assertEquals(400L, after.confirmation.amount)
        assertTrue(after.timeline.any { it.debugId == "debug-authorize" && it.providerId == "authorization-1" })
        assertFalse(after.timeline.toString().contains("buyer@example.test"))
    }

    @Test
    fun `amount and currency mismatches release the hold without counting it`() {
        for (providerMoney in listOf(Money(201, "USD"), Money(200, "EUR"))) {
            val fixture = fixture()
            val pledge = created(fixture)
            gateway.fake.authorizeOrderResults.add(received(AuthorizationDetails(
                AuthorizationId("authorization-${pledge.id}"), providerMoney, start.plusSeconds(86_400),
                orderId = OrderId(pledge.paypalOrderId!!),
            ), PaymentStatus.CREATED))
            gateway.fake.voidAuthorizationResults.add(received(VoidDetails(AuthorizationId("authorization-${pledge.id}")), PaymentStatus.VOIDED))
            val rejected = rejected(service.completePledge(PledgeRef(fixture.ref, pledge.id)))
            assertEquals(Rejection.PAYMENT_MISMATCH, rejected.reason)
            assertEquals(PledgeState.VOIDED, rejected.pledge?.state)
            assertEquals(0L, success(service.readCampaign(fixture.ref)).counts.authorizedHolds)
        }
    }

    @Test
    fun `simultaneous approvals cannot exceed capacity and losing hold is voided`() {
        val fixture = fixture(minimum = 1, capacity = 1)
        val first = created(fixture)
        val second = created(fixture)
        gateway.fake.authorizeOrderResults.add(received(AuthorizationDetails(AuthorizationId("race-1"), Money(200, "USD"), null), PaymentStatus.CREATED))
        gateway.fake.authorizeOrderResults.add(received(AuthorizationDetails(AuthorizationId("race-2"), Money(200, "USD"), null), PaymentStatus.CREATED))
        gateway.fake.voidAuthorizationResults.add(received(VoidDetails(AuthorizationId("race-2")), PaymentStatus.VOIDED))
        val executor = Executors.newFixedThreadPool(2)
        val go = CountDownLatch(1)
        try {
            val tasks = listOf(first, second).map { pledge ->
                executor.submit<ServiceResult<PledgeView>> {
                    assertTrue(go.await(5, TimeUnit.SECONDS))
                    service.completePledge(PledgeRef(fixture.ref, pledge.id))
                }
            }
            go.countDown()
            val results = tasks.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is ServiceResult.Success })
            val rejected = assertIs<ServiceResult.Rejected>(results.single { it is ServiceResult.Rejected })
            assertEquals(Rejection.CAPACITY_EXCEEDED, rejected.reason)
            assertEquals(PledgeState.VOIDED, rejected.pledge?.state)
            healthyAuthorization(pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(fixture.campaign.id)
                .single { it.state == PledgeState.AUTHORIZED })
            assertEquals(1L, success(service.readCampaign(fixture.ref)).counts.authorizedHolds)
            assertEquals(1, gateway.fake.calls.filterIsInstance<PaymentCall.VoidAuthorization>().size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `closing below minimum releases authorizations on lazy read`() {
        val fixture = fixture(minimum = 2)
        val pledge = authorized(fixture, 1)
        voidSuccess(pledge)
        clock.current = start.plusSeconds(901)
        healthyAuthorization(pledge)
        val result = success(service.readCampaign(fixture.ref))
        assertEquals(CampaignState.NOT_FUNDED, result.state)
        assertEquals(CampaignReason.THRESHOLD_NOT_MET, result.reason)
        assertEquals(PledgeState.VOIDED, result.pledges.single().state)
        assertTrue(result.needsAttention.isEmpty())
    }

    @Test
    fun `close waits for confirmation then expiry releases holds even after downtime`() {
        val fixture = fixture(minimum = 1)
        val pledge = authorized(fixture, 1)
        clock.current = start.plusSeconds(901)
        healthyAuthorization(pledge)
        assertEquals(CampaignState.AWAITING_CONFIRMATION, success(service.reconcile(fixture.ref)).state)
        assertTrue(gateway.fake.calls.all { it is PaymentCall.ReadAuthorization })
        clock.current = start.plusSeconds(44_101)
        voidSuccess(pledge)
        healthyAuthorization(pledge)
        val result = success(service.readCampaign(fixture.ref))
        assertEquals(CampaignState.NOT_FUNDED, result.state)
        assertEquals(CampaignReason.CONFIRMATION_EXPIRED, result.reason)
        assertEquals(PledgeState.VOIDED, result.pledges.single().state)
    }

    @Test
    fun `first read after both deadlines expires a threshold campaign`() {
        val fixture = fixture(minimum = 1)
        val pledge = authorized(fixture, 1)
        clock.current = start.plusSeconds(44_101)
        voidSuccess(pledge)
        healthyAuthorization(pledge)
        val result = success(service.readCampaign(fixture.ref))
        assertEquals(CampaignReason.CONFIRMATION_EXPIRED, result.reason)
        assertEquals(CampaignState.NOT_FUNDED, result.state)
    }

    @Test
    fun `maker cancellation releases every authorization and records calls`() {
        val fixture = fixture()
        val first = authorized(fixture, 1)
        val second = authorized(fixture, 1)
        voidSuccess(first)
        voidSuccess(second)
        val result = success(service.cancel(fixture.ref))
        assertEquals(CampaignReason.CANCELLED_BY_MAKER, result.reason)
        assertEquals(setOf(PledgeState.VOIDED), result.pledges.map { it.state }.toSet())
        assertEquals(2, gateway.fake.calls.filterIsInstance<PaymentCall.VoidAuthorization>().size)
        assertTrue(result.timeline.any { it.endpoint?.endsWith("/void") == true && it.status == "VOIDED" })
        assertTrue(result.timeline.any { it.detail?.contains("CANCELLED_BY_MAKER") == true || it.type.contains("NOT_FUNDED") })
    }

    @Test
    fun `completed captures can fund despite another failure and confirmation is idempotent`() {
        val fixture = fixture(minimum = 2)
        val first = authorized(fixture, 1)
        val failed = authorized(fixture, 1)
        val third = authorized(fixture, 1)
        captureSuccess(first)
        gateway.fake.captureResults.add(PaymentResult.Failed(PaymentStatus.DECLINED, "debug-declined"))
        captureSuccess(third)
        voidSuccess(failed)
        val funded = success(service.confirmProduction(fixture.ref))
        assertEquals(CampaignState.FUNDED, funded.state)
        assertEquals(2L, funded.counts.paid)
        assertTrue(funded.result.productionReleased)
        assertEquals(listOf(first.authorizationId, failed.authorizationId, third.authorizationId),
            gateway.fake.calls.filterIsInstance<PaymentCall.Capture>().map { it.authorizationId.value })
        assertEquals(PledgeState.VOIDED, funded.pledges.single { it.id == failed.id }.state)
        val count = gateway.fake.calls.size
        assertEquals(CampaignState.FUNDED, success(service.confirmProduction(fixture.ref)).state)
        assertEquals(count, gateway.fake.calls.size)
    }

    @Test
    fun `shortfall refunds completed captures and voids failed authorizations`() {
        val fixture = fixture(minimum = 2)
        val first = authorized(fixture, 1)
        val failed = authorized(fixture, 1)
        captureSuccess(first)
        gateway.fake.captureResults.add(PaymentResult.Failed(PaymentStatus.DECLINED))
        refundSuccess(first)
        voidSuccess(failed)
        val result = success(service.confirmProduction(fixture.ref))
        assertEquals(CampaignState.NOT_FUNDED, result.state)
        assertEquals(CampaignReason.CAPTURE_SHORTFALL, result.reason)
        assertFalse(result.result.productionReleased)
        assertEquals(PledgeState.REFUNDED, result.pledges.single { it.id == first.id }.state)
        assertEquals(PledgeState.VOIDED, result.pledges.single { it.id == failed.id }.state)
        assertEquals(0L, result.counts.paid)
        assertTrue(result.needsAttention.isEmpty())
        assertEquals(idempotencyKey(first.id, PaymentAction.REFUND),
            gateway.fake.calls.filterIsInstance<PaymentCall.RefundCapture>().single().idempotencyKey)
    }

    @Test
    fun `pending capture is not paid and late completion after shortfall is refunded`() {
        val fixture = fixture(minimum = 1)
        val pledge = authorized(fixture, 1)
        gateway.fake.captureResults.add(received(captureDetails(pledge), PaymentStatus.PENDING))
        val pending = success(service.confirmProduction(fixture.ref))
        assertEquals(CampaignState.NOT_FUNDED, pending.state)
        assertEquals(0L, pending.counts.paid)
        assertEquals(PledgeState.CAPTURE_PENDING, pending.pledges.single().state)
        assertEquals(pledge.id, pending.needsAttention.single().pledgeId)
        gateway.fake.readCaptureResults.add(received(captureDetails(pledge), PaymentStatus.COMPLETED))
        refundSuccess(pledge)
        val settled = success(service.reconcile(fixture.ref))
        assertEquals(CampaignState.NOT_FUNDED, settled.state)
        assertEquals(PledgeState.REFUNDED, settled.pledges.single().state)
        assertTrue(settled.needsAttention.isEmpty())
    }

    @Test
    fun `pending compensation remains visible until provider completion`() {
        val fixture = fixture(minimum = 2)
        val first = authorized(fixture, 1)
        val failed = authorized(fixture, 1)
        captureSuccess(first)
        gateway.fake.captureResults.add(PaymentResult.Failed(PaymentStatus.DECLINED))
        gateway.fake.refundCaptureResults.add(received(refundDetails(first), PaymentStatus.PENDING))
        gateway.fake.voidAuthorizationResults.add(PaymentResult.Failed(PaymentStatus.FAILED))
        val result = success(service.confirmProduction(fixture.ref))
        assertEquals(CampaignState.NOT_FUNDED, result.state)
        assertEquals(setOf(PledgeState.REFUND_PENDING, PledgeState.VOID_FAILED), result.needsAttention.map { it.state }.toSet())
        gateway.fake.readRefundResults.add(received(refundDetails(first), PaymentStatus.COMPLETED))
        voidSuccess(failed)
        clock.current = start.plusSeconds(60)
        val reconciled = success(service.reconcile(fixture.ref))
        assertTrue(reconciled.needsAttention.isEmpty())
        assertEquals(setOf(PledgeState.REFUNDED, PledgeState.VOIDED), reconciled.pledges.map { it.state }.toSet())
    }

    @Test
    fun `crash after provider capture persists intent and retry resolves provider without another capture`() {
        val fixture = fixture(minimum = 2)
        val first = authorized(fixture, 1)
        val second = authorized(fixture, 1)
        captureSuccess(first)
        captureSuccess(second)
        gateway.crashAfterCaptureNumber = 2
        assertFailsWith<SimulatedProcessCrash> { service.confirmProduction(fixture.ref) }
        assertEquals(CampaignState.SETTLING, campaigns.findById(fixture.campaign.id).orElseThrow().state)
        assertEquals(PledgeState.CAPTURED, pledges.findById(first.id).orElseThrow().state)
        assertTrue(events.findByCampaignIdOrderByCreatedAtAscIdAsc(fixture.campaign.id).any {
            it.pledgeId == second.id && it.paypalEndpoint?.endsWith("/capture") == true
        })
        gateway.fake.readAuthorizationResults.add(received(AuthorizationDetails(
            AuthorizationId(second.authorizationId!!), Money(second.amount, second.currency), null,
            captureId = CaptureId("capture-${second.id}"),
        ), PaymentStatus.CAPTURED))
        gateway.fake.readCaptureResults.add(received(captureDetails(second), PaymentStatus.COMPLETED))
        val retried = success(service.confirmProduction(fixture.ref))
        assertEquals(CampaignState.FUNDED, retried.state)
        assertEquals(2L, retried.counts.paid)
        assertEquals(2, gateway.fake.calls.filterIsInstance<PaymentCall.Capture>().size)
    }

    @Test
    fun `ambiguous capture transport failures resolve provider evidence before deciding failure`() {
        val fixture = fixture(minimum = 1)
        val pledge = authorized(fixture, 1)
        gateway.fake.captureResults.add(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE))
        gateway.fake.readAuthorizationResults.add(received(AuthorizationDetails(
            AuthorizationId(pledge.authorizationId!!), Money(pledge.amount, pledge.currency), null,
            captureId = CaptureId("capture-${pledge.id}"),
        ), PaymentStatus.CAPTURED))
        gateway.fake.readCaptureResults.add(received(captureDetails(pledge), PaymentStatus.COMPLETED))
        val result = success(service.confirmProduction(fixture.ref))
        assertEquals(CampaignState.FUNDED, result.state)
        assertEquals(PledgeState.CAPTURED, result.pledges.single().state)
        val captures = gateway.fake.calls.filterIsInstance<PaymentCall.Capture>()
        assertEquals(1, captures.size)
        assertEquals(setOf(idempotencyKey(pledge.id, PaymentAction.CAPTURE)), captures.map { it.idempotencyKey }.toSet())
        assertFalse(result.timeline.any { it.type == "PLEDGE_STATE" && it.detail?.endsWith("CAPTURE_FAILED") == true })
    }

    @Test
    fun `crash after refund replays original key and never recaptures`() {
        val fixture = fixture(minimum = 2)
        val first = authorized(fixture, 1)
        val failed = authorized(fixture, 1)
        captureSuccess(first)
        gateway.fake.captureResults.add(PaymentResult.Failed(PaymentStatus.DECLINED))
        refundSuccess(first)
        gateway.crashAfterRefund = true
        assertFailsWith<SimulatedProcessCrash> { service.confirmProduction(fixture.ref) }
        assertEquals(CampaignState.NOT_FUNDED, campaigns.findById(fixture.campaign.id).orElseThrow().state)
        refundSuccess(first)
        voidSuccess(failed)
        healthyCapture(first)
        val result = success(service.reconcile(fixture.ref))
        assertEquals(PledgeState.REFUNDED, result.pledges.single { it.id == first.id }.state)
        assertEquals(2, gateway.fake.calls.filterIsInstance<PaymentCall.Capture>().size)
        val refunds = gateway.fake.calls.filterIsInstance<PaymentCall.RefundCapture>()
        assertEquals(2, refunds.size)
        assertEquals(setOf(idempotencyKey(first.id, PaymentAction.REFUND)), refunds.map { it.idempotencyKey }.toSet())
    }

    @Test
    fun `approval callback after cancellation cannot create a new hold`() {
        val fixture = fixture()
        val pledge = created(fixture)
        success(service.cancel(fixture.ref))
        val result = rejected(service.completePledge(PledgeRef(fixture.ref, pledge.id)))
        assertEquals(Rejection.INVALID_STATE, result.reason)
        assertEquals(PledgeState.ABANDONED, result.pledge?.state)
        assertTrue(gateway.fake.calls.isEmpty())
    }

    @Test
    fun `campaign closing during authorization releases the newly returned hold`() {
        val fixture = fixture(minimum = 2)
        val pledge = created(fixture)
        gateway.fake.authorizeOrderResults.add(received(AuthorizationDetails(
            AuthorizationId("late-authorization"), Money(200, "USD"), null,
        ), PaymentStatus.CREATED))
        gateway.afterAuthorization = { clock.current = start.plusSeconds(901) }
        gateway.fake.voidAuthorizationResults.add(received(VoidDetails(AuthorizationId("late-authorization")), PaymentStatus.VOIDED))
        val result = rejected(service.completePledge(PledgeRef(fixture.ref, pledge.id)))
        assertEquals(Rejection.INVALID_STATE, result.reason)
        assertEquals(PledgeState.VOIDED, result.pledge?.state)
        assertEquals(CampaignReason.THRESHOLD_NOT_MET, success(service.readCampaign(fixture.ref)).reason)
    }

    @Test
    fun `provider captured evidence overrides failed void and refunds a shortfall payment`() {
        val fixture = fixture(minimum = 1)
        fixture.campaign.state = CampaignState.NOT_FUNDED
        fixture.campaign.reason = CampaignReason.CAPTURE_SHORTFALL
        campaigns.saveAndFlush(fixture.campaign)
        val pledge = authorized(fixture, 1)
        pledge.state = PledgeState.VOID_FAILED
        pledges.saveAndFlush(pledge)
        gateway.fake.voidAuthorizationResults.add(PaymentResult.Failed(PaymentStatus.ALREADY_CAPTURED))
        gateway.fake.readAuthorizationResults.add(received(AuthorizationDetails(
            AuthorizationId(pledge.authorizationId!!), Money(pledge.amount, pledge.currency), null,
            captureId = CaptureId("capture-${pledge.id}"),
        ), PaymentStatus.CAPTURED))
        gateway.fake.readCaptureResults.add(received(captureDetails(pledge), PaymentStatus.COMPLETED))
        refundSuccess(pledge)
        val result = success(service.reconcile(fixture.ref))
        assertEquals(CampaignState.NOT_FUNDED, result.state)
        assertEquals(PledgeState.REFUNDED, result.pledges.single().state)
        assertTrue(result.needsAttention.isEmpty())
        assertTrue(gateway.fake.calls.filterIsInstance<PaymentCall.Capture>().isEmpty())
    }

    @Test
    fun `authorization captured status alone cannot count an unresolved capture as paid`() {
        val fixture = fixture(minimum = 1)
        val pledge = authorized(fixture, 1)
        gateway.fake.captureResults.add(PaymentResult.Failed(PaymentStatus.ALREADY_CAPTURED))
        gateway.fake.readAuthorizationResults.add(received(AuthorizationDetails(
            AuthorizationId(pledge.authorizationId!!), Money(pledge.amount, pledge.currency), null,
            captureId = CaptureId("capture-${pledge.id}"),
        ), PaymentStatus.CAPTURED))
        gateway.fake.readCaptureResults.add(received(captureDetails(pledge), PaymentStatus.PENDING))
        val result = success(service.confirmProduction(fixture.ref))
        assertEquals(CampaignState.NOT_FUNDED, result.state)
        assertEquals(0L, result.counts.paid)
        assertEquals(PledgeState.CAPTURE_PENDING, result.needsAttention.single().state)
    }

    @Test
    fun `simulation is disabled by default and never arms a fault`() {
        val fixture = fixture()
        assertEquals(Rejection.SIMULATION_DISABLED, rejected(service.simulateNextCaptureFailure(fixture.ref)).reason)
        assertTrue(events.findByCampaignIdOrderByCreatedAtAscIdAsc(fixture.campaign.id).none { it.type == "SIMULATION_ARMED" })
    }

    @Test
    fun `offer rejects overflowing totals and missing proposal`() {
        val fixture = fixture()
        assertEquals(Rejection.AMOUNT_OVERFLOW,
            rejected(service.createDraft(fixture.workspace.id, draft(fixture).copy(unitPrice = Long.MAX_VALUE))).reason)
        val output = AnalysisOutput(emptyList(), emptyList(), null, "No feasible option", emptyList(), emptyList(), emptyList(), listOf(fixture.option.id))
        val noProposal = analyses.saveAndFlush(AnalysisRun(fixture.workspace.id, "synthetic", "hash",
            mapper.writeValueAsString(output), createdAt = start))
        assertEquals(Rejection.NO_PROPOSAL,
            rejected(service.createDraft(fixture.workspace.id, draft(fixture).copy(analysisRunId = noProposal.id))).reason)
    }

    @Test
    fun `resumed settlement refreshes earlier pending capture before deciding funding`() {
        val fixture = fixture(minimum = 2)
        val pending = authorized(fixture, 1)
        val interrupted = authorized(fixture, 1)
        gateway.fake.captureResults.add(received(captureDetails(pending), PaymentStatus.PENDING))
        captureSuccess(interrupted)
        gateway.crashAfterCaptureNumber = 2
        assertFailsWith<SimulatedProcessCrash> { service.confirmProduction(fixture.ref) }
        assertEquals(CampaignState.SETTLING, campaigns.findById(fixture.campaign.id).orElseThrow().state)
        assertEquals(PledgeState.CAPTURE_PENDING, pledges.findById(pending.id).orElseThrow().state)
        gateway.fake.readCaptureResults.add(received(captureDetails(pending), PaymentStatus.COMPLETED))
        gateway.fake.readAuthorizationResults.add(received(AuthorizationDetails(
            AuthorizationId(interrupted.authorizationId!!), Money(interrupted.amount, interrupted.currency), null,
            captureId = CaptureId("capture-${interrupted.id}"),
        ), PaymentStatus.CAPTURED))
        gateway.fake.readCaptureResults.add(received(captureDetails(interrupted), PaymentStatus.COMPLETED))
        val result = success(service.confirmProduction(fixture.ref))
        assertEquals(CampaignState.FUNDED, result.state)
        assertEquals(2L, result.counts.paid)
        assertTrue(result.needsAttention.isEmpty())
        assertEquals(2, gateway.fake.calls.filterIsInstance<PaymentCall.Capture>().size)
        assertTrue(gateway.fake.calls.filterIsInstance<PaymentCall.RefundCapture>().isEmpty())
    }

    @Test
    fun `failed known refund is read before retrying its original request key`() {
        val fixture = fixture(minimum = 2)
        val first = authorized(fixture, 1)
        val failed = authorized(fixture, 1)
        captureSuccess(first)
        gateway.fake.captureResults.add(PaymentResult.Failed(PaymentStatus.DECLINED))
        gateway.fake.refundCaptureResults.add(received(refundDetails(first), PaymentStatus.FAILED))
        voidSuccess(failed)
        val initial = success(service.confirmProduction(fixture.ref))
        assertEquals(PledgeState.REFUND_FAILED, initial.needsAttention.single().state)
        gateway.fake.readRefundResults.add(received(refundDetails(first), PaymentStatus.FAILED))
        refundSuccess(first)
        clock.current = start.plusSeconds(60)
        val result = success(service.reconcile(fixture.ref))
        assertEquals(PledgeState.REFUNDED, result.pledges.single { it.id == first.id }.state)
        assertTrue(result.needsAttention.isEmpty())
        val refunds = gateway.fake.calls.filterIsInstance<PaymentCall.RefundCapture>()
        assertEquals(2, refunds.size)
        assertEquals(setOf(idempotencyKey(first.id, PaymentAction.REFUND)), refunds.map { it.idempotencyKey }.toSet())
        assertEquals(1, gateway.fake.calls.filterIsInstance<PaymentCall.ReadRefund>().size)
    }

    @Test
    fun `declined capture evidence cannot erase an unconfirmed void`() {
        val fixture = fixture(minimum = 1)
        fixture.campaign.state = CampaignState.NOT_FUNDED
        fixture.campaign.reason = CampaignReason.CAPTURE_SHORTFALL
        campaigns.saveAndFlush(fixture.campaign)
        val pledge = authorized(fixture, 1)
        pledge.state = PledgeState.VOID_FAILED
        pledges.saveAndFlush(pledge)
        gateway.fake.voidAuthorizationResults.add(PaymentResult.Failed(PaymentStatus.ALREADY_CAPTURED))
        gateway.fake.readAuthorizationResults.add(received(AuthorizationDetails(
            AuthorizationId(pledge.authorizationId!!), Money(pledge.amount, pledge.currency), null,
            captureId = CaptureId("capture-${pledge.id}"),
        ), PaymentStatus.CAPTURED))
        gateway.fake.readCaptureResults.add(received(captureDetails(pledge), PaymentStatus.DECLINED))
        val unresolved = success(service.reconcile(fixture.ref))
        assertEquals(PledgeState.VOID_FAILED, unresolved.needsAttention.single().state)
        voidSuccess(pledge)
        val resolved = success(service.reconcile(fixture.ref))
        assertEquals(PledgeState.VOIDED, resolved.pledges.single().state)
        assertTrue(resolved.needsAttention.isEmpty())
    }

    @Test
    fun `enabled demo simulation fails only the next capture and records its synthetic source`() {
        val simulationService = CampaignService(persistence, offers, options, analyses, campaigns,
            pledges, events, payments, mapper, clock, true)
        val fixture = fixture(minimum = 2)
        val simulated = authorized(fixture, 1)
        val captured = authorized(fixture, 1)
        success(simulationService.simulateNextCaptureFailure(fixture.ref))
        success(simulationService.simulateNextCaptureFailure(fixture.ref))
        captureSuccess(captured)
        refundSuccess(captured)
        voidSuccess(simulated)
        val result = success(simulationService.confirmProduction(fixture.ref))
        assertEquals(CampaignReason.CAPTURE_SHORTFALL, result.reason)
        assertEquals(PledgeState.VOIDED, result.pledges.single { it.id == simulated.id }.state)
        assertEquals(PledgeState.REFUNDED, result.pledges.single { it.id == captured.id }.state)
        assertEquals(listOf(captured.authorizationId), gateway.fake.calls.filterIsInstance<PaymentCall.Capture>().map { it.authorizationId.value })
        assertEquals(1, result.timeline.count { it.type == "SIMULATION_ARMED" })
        assertEquals(1, result.timeline.count { it.type == "SIMULATED_CAPTURE_FAILURE" && it.pledgeId == simulated.id })
        assertTrue(result.timeline.none { it.type == "CAPTURE_REQUEST" && it.pledgeId == simulated.id })
    }

    @Test
    fun `scheduled tick applies deadline transitions without a campaign page read`() {
        val fixture = fixture(minimum = 2)
        val pledge = authorized(fixture, 1)
        clock.current = start.plusSeconds(901)
        voidSuccess(pledge)
        healthyAuthorization(pledge)
        tick.tick()
        assertEquals(CampaignState.NOT_FUNDED, campaigns.findById(fixture.campaign.id).orElseThrow().state)
        assertEquals(CampaignReason.THRESHOLD_NOT_MET, campaigns.findById(fixture.campaign.id).orElseThrow().reason)
        assertEquals(PledgeState.VOIDED, pledges.findById(pledge.id).orElseThrow().state)
    }

    @Test
    fun `unresolved capture on a terminal campaign never creates a new payment`() {
        val fixture = fixture(minimum = 1)
        fixture.campaign.state = CampaignState.NOT_FUNDED
        fixture.campaign.reason = CampaignReason.CAPTURE_SHORTFALL
        campaigns.saveAndFlush(fixture.campaign)
        val pledge = authorized(fixture, 1)
        pledge.state = PledgeState.CAPTURE_PENDING
        pledges.saveAndFlush(pledge)
        gateway.fake.readAuthorizationResults.add(received(AuthorizationDetails(
            AuthorizationId(pledge.authorizationId!!), Money(pledge.amount, pledge.currency), null,
        ), PaymentStatus.CREATED))
        voidSuccess(pledge)
        val result = success(service.reconcile(fixture.ref))
        assertEquals(CampaignState.NOT_FUNDED, result.state)
        assertEquals(PledgeState.VOIDED, result.pledges.single().state)
        assertTrue(result.needsAttention.isEmpty())
        assertTrue(gateway.fake.calls.filterIsInstance<PaymentCall.Capture>().isEmpty())
        assertEquals(1, gateway.fake.calls.filterIsInstance<PaymentCall.VoidAuthorization>().size)
    }

    @Test
    fun `resumed settlement after capture deadline releases an unresolved hold without charging`() {
        val fixture = fixture(minimum = 1)
        fixture.campaign.state = CampaignState.SETTLING
        campaigns.saveAndFlush(fixture.campaign)
        val pledge = authorized(fixture, 1)
        pledge.state = PledgeState.CAPTURE_PENDING
        pledges.saveAndFlush(pledge)
        clock.current = start.plusSeconds(44_101)
        gateway.fake.readAuthorizationResults.add(received(AuthorizationDetails(
            AuthorizationId(pledge.authorizationId!!), Money(pledge.amount, pledge.currency), null,
        ), PaymentStatus.CREATED))
        voidSuccess(pledge)
        val result = success(service.confirmProduction(fixture.ref))
        assertEquals(CampaignState.NOT_FUNDED, result.state)
        assertEquals(CampaignReason.CAPTURE_SHORTFALL, result.reason)
        assertEquals(PledgeState.VOIDED, result.pledges.single().state)
        assertTrue(gateway.fake.calls.filterIsInstance<PaymentCall.Capture>().isEmpty())
    }

    @Test
    fun `ambiguous authorization followed by resource failure remains visible for reconciliation`() {
        val fixture = fixture()
        val pledge = created(fixture)
        gateway.fake.authorizeOrderResults.add(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE))
        gateway.fake.authorizeOrderResults.add(PaymentResult.Failed(PaymentStatus.FAILED))
        val result = rejected(service.completePledge(PledgeRef(fixture.ref, pledge.id)))
        assertEquals(Rejection.PAYMENT_REJECTED, result.reason)
        assertEquals(PledgeState.CREATED, result.pledge?.state)
        val retried = rejected(service.completePledge(PledgeRef(fixture.ref, pledge.id)))
        assertEquals(PledgeState.CREATED, retried.pledge?.state)
        val persisted = pledges.findById(pledge.id).orElseThrow()
        assertEquals(PledgeState.CREATED, persisted.state)
        assertEquals("AUTHORIZATION_UNCONFIRMED", persisted.lastError)
        assertTrue(campaigns.findById(fixture.campaign.id).orElseThrow().needsAttention)
        assertTrue(events.findByCampaignIdOrderByCreatedAtAscIdAsc(fixture.campaign.id).none {
            it.type == "PLEDGE_STATE" && it.detail?.endsWith("ABANDONED") == true
        })
        val attempts = gateway.fake.calls.filterIsInstance<PaymentCall.AuthorizeOrder>()
        assertEquals(2, attempts.size)
        assertEquals(setOf(idempotencyKey(pledge.id, PaymentAction.AUTHORIZE)), attempts.map { it.idempotencyKey }.toSet())
    }

    @Test
    fun `confirmation is guarded below threshold and remains allowed at exact deadline`() {
        val fixture = fixture(minimum = 2)
        val first = authorized(fixture, 1)
        assertEquals(Rejection.THRESHOLD_NOT_MET, rejected(service.confirmProduction(fixture.ref)).reason)
        assertTrue(gateway.fake.calls.isEmpty())
        val second = authorized(fixture, 1)
        clock.current = start.plusSeconds(44_100)
        captureSuccess(first)
        captureSuccess(second)
        assertEquals(CampaignState.FUNDED, success(service.confirmProduction(fixture.ref)).state)
    }

    @Test
    fun `current campaign exposes offer terms and reconciles its deadline`() {
        val fixture = fixture()
        val view = assertNotNull(success(queries.currentCampaign(fixture.workspace.token)))
        assertEquals(fixture.campaign.id, view.id)
        assertEquals(fixture.offer.title, view.offer.title)
        assertEquals(fixture.offer.specText, view.offer.specText)
        assertEquals(fixture.offer.unitPrice, view.offer.unitPrice)
        assertEquals(fixture.offer.deliveryTerms, view.offer.deliveryTerms)
        assertEquals(fixture.offer.shipRegion, view.offer.shipRegion)
        assertEquals(fixture.offer.minBatch, view.offer.minBatch)
        assertEquals(fixture.offer.capacity, view.offer.capacity)
        assertEquals(fixture.offer.maxQtyPerPledge, view.offer.maxQtyPerPledge)
        assertEquals(fixture.campaign.closesAt, view.closesAt)
        assertTrue(queries.isOpenForPledges(fixture.workspace.token))

        clock.current = fixture.campaign.closesAt!!
        assertFalse(queries.isOpenForPledges(fixture.workspace.token))
        assertEquals(CampaignState.OPEN, campaigns.findById(fixture.campaign.id).orElseThrow().state)
        assertEquals(CampaignState.NOT_FUNDED,
            assertNotNull(success(queries.currentCampaign(fixture.workspace.token))).state)
        assertTrue(gateway.fake.calls.isEmpty())
    }

    @Test
    fun `current campaign selects confirmation and settlement states before terminal history`() {
        listOf(CampaignState.AWAITING_CONFIRMATION, CampaignState.SETTLING).forEach { state ->
            val fixture = fixture(state = state)
            campaigns.saveAndFlush(Campaign(fixture.offer.id, CampaignState.FUNDED,
                openedAt = start.plusSeconds(1), closesAt = start.plusSeconds(901),
                confirmDeadline = start.plusSeconds(44_101)))
            val current = assertNotNull(success(queries.currentCampaign(fixture.workspace.token)))
            assertEquals(fixture.campaign.id, current.id)
            assertEquals(if (state == CampaignState.SETTLING) CampaignState.NOT_FUNDED else state, current.state)
            assertFalse(queries.isOpenForPledges(fixture.workspace.token))
        }
        assertTrue(gateway.fake.calls.isEmpty())
    }

    @Test
    fun `current campaign excludes drafts and unknown workspaces`() {
        val fixture = fixture(state = CampaignState.DRAFT)
        assertNull(success(queries.currentCampaign(fixture.workspace.token)))
        assertFalse(queries.isOpenForPledges(fixture.workspace.token))
        assertEquals(Rejection.NOT_FOUND, rejected(queries.currentCampaign("missing-token")).reason)
        assertFalse(queries.isOpenForPledges("missing-token"))
        val empty = workspaces.saveAndFlush(Workspace("empty-${UUID.randomUUID()}", start))
        ownedWorkspaces.add(empty.id)
        assertNull(success(queries.currentCampaign(empty.token)))
    }

    @Test
    fun `quotes compute exact totals and capacity decisions without creating anything`() {
        val fixture = fixture(capacity = 5)
        authorized(fixture, 3)
        created(fixture)
        val pledgeCount = pledges.count()
        val eventCount = events.count()
        val quote = success(queries.quote(fixture.workspace.token, 2))
        assertEquals(fixture.campaign.id, quote.campaignId)
        assertEquals(2, quote.quantity)
        assertEquals(400L, quote.amount)
        assertEquals("USD", quote.currency)
        assertEquals(CapacityDecision.ALLOWED, quote.decision)
        assertTrue(quote.allowed)
        assertEquals(CapacityDecision.CAPACITY_EXCEEDED,
            success(queries.quote(fixture.workspace.token, 3)).decision)
        listOf(0, -1, 4).forEach { quantity ->
            val invalid = success(queries.quote(fixture.workspace.token, quantity))
            assertEquals(CapacityDecision.INVALID_QUANTITY, invalid.decision)
            assertFalse(invalid.allowed)
        }
        assertEquals(pledgeCount, pledges.count())
        assertEquals(eventCount, events.count())
        assertTrue(gateway.fake.calls.isEmpty())
        clock.current = fixture.campaign.closesAt!!
        assertEquals(Rejection.INVALID_STATE, rejected(queries.quote(fixture.workspace.token, 1)).reason)
        assertEquals(CampaignState.OPEN, campaigns.findById(fixture.campaign.id).orElseThrow().state)
    }

    @Test
    fun `quotes reject totals that cannot fit minor units`() {
        val fixture = fixture(state = CampaignState.DRAFT)
        val draft = success(service.createDraft(fixture.workspace.id,
            draft(fixture).copy(unitPrice = Long.MAX_VALUE / fixture.offer.capacity)))
        success(service.publish(CampaignRef(fixture.workspace.id, draft.campaignId), Duration.ofMinutes(15)))
        assertEquals(Rejection.AMOUNT_OVERFLOW,
            rejected(queries.quote(fixture.workspace.token, Int.MAX_VALUE)).reason)
        assertTrue(pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(draft.campaignId).isEmpty())
        assertTrue(gateway.fake.calls.isEmpty())
    }

    @Test
    fun `token queries isolate campaign quotes pledges and maker offers`() {
        val fixture = fixture()
        val other = fixture(state = CampaignState.DRAFT)
        val pledge = created(fixture)
        assertNull(success(queries.currentCampaign(other.workspace.token)))
        assertFalse(queries.isOpenForPledges(other.workspace.token))
        assertEquals(Rejection.INVALID_STATE, rejected(queries.quote(other.workspace.token, 1)).reason)
        assertEquals(Rejection.NOT_FOUND, rejected(queries.quote("missing-token", 1)).reason)
        assertEquals(Rejection.NOT_FOUND, rejected(queries.lookupPledge(other.workspace.token, pledge.id)).reason)
        assertEquals(Rejection.NOT_FOUND, rejected(queries.lookupPledge("missing-token", pledge.id)).reason)
        assertEquals(Rejection.NOT_FOUND,
            rejected(queries.lookupPledge(fixture.workspace.token, UUID.randomUUID())).reason)
        val maker = success(queries.makerOffers(other.workspace.token))
        assertEquals(other.campaign.id, maker.draft?.id)
        assertTrue(maker.history.isEmpty())
        assertEquals(Rejection.NOT_FOUND, rejected(queries.makerOffers("missing-token")).reason)
        assertTrue(gateway.fake.calls.isEmpty())
    }

    @Test
    fun `pledge lookup includes timestamps amount and reconciled campaign`() {
        val fixture = fixture()
        val pledge = created(fixture)
        val lookup = success(queries.lookupPledge(fixture.workspace.token, pledge.id))
        assertEquals(pledge.id, lookup.pledge.id)
        assertEquals(pledge.qty, lookup.pledge.quantity)
        assertEquals(pledge.amount, lookup.pledge.amount)
        assertEquals(pledge.currency, lookup.pledge.currency)
        assertEquals(pledge.createdAt, lookup.createdAt)
        assertEquals(pledge.authorizationExpiresAt, lookup.pledge.authorizationExpiresAt)
        assertEquals(PledgeState.CREATED, lookup.pledge.state)
        assertEquals(fixture.campaign.id, lookup.campaign.id)
        clock.current = fixture.campaign.closesAt!!
        val closed = success(queries.lookupPledge(fixture.workspace.token, pledge.id))
        assertEquals(CampaignState.NOT_FUNDED, closed.campaign.state)
        assertEquals(PledgeState.ABANDONED, closed.pledge.state)
    }

    @Test
    fun `maker queries choose latest draft and order terminal history with active precedence`() {
        val fixture = fixture(state = CampaignState.FUNDED)
        clock.current = start.plusSeconds(1)
        val second = success(service.createDraft(fixture.workspace.id, draft(fixture)))
        val secondRef = CampaignRef(fixture.workspace.id, second.campaignId)
        success(service.publish(secondRef, Duration.ofMinutes(15)))
        assertEquals(second.campaignId, assertNotNull(success(queries.currentCampaign(fixture.workspace.token))).id)
        success(service.cancel(secondRef))
        assertEquals(second.campaignId, assertNotNull(success(queries.currentCampaign(fixture.workspace.token))).id)
        clock.current = start.plusSeconds(2)
        success(service.createDraft(fixture.workspace.id, draft(fixture)))
        clock.current = start.plusSeconds(3)
        val latestDraft = success(service.createDraft(fixture.workspace.id, draft(fixture)))
        val maker = success(queries.makerOffers(fixture.workspace.token))
        assertEquals(latestDraft.campaignId, maker.draft?.id)
        assertEquals(second.campaignId, maker.latest?.id)
        assertEquals(listOf(second.campaignId, fixture.campaign.id), maker.history.map { it.id })
        assertEquals(CampaignReason.CANCELLED_BY_MAKER, maker.history.first().result.reason)
        assertTrue(maker.history.last().result.productionReleased)
        assertTrue(gateway.fake.calls.isEmpty())
    }

    @Test
    fun `withdrawal releases a hold once with durable events and reduces authorized units`() {
        val fixture = fixture()
        val pledge = authorized(fixture, 2)
        val ref = PledgeRef(fixture.ref, pledge.id)
        healthyAuthorization(pledge)
        assertTrue(success(queries.lookupPledge(fixture.workspace.token, pledge.id)).canWithdraw)
        voidSuccess(pledge)

        assertEquals(PledgeState.VOIDED, success(service.withdrawPledge(ref)).state)
        assertEquals(PledgeState.VOIDED, success(service.withdrawPledge(ref)).state)
        val call = gateway.fake.calls.filterIsInstance<PaymentCall.VoidAuthorization>().single()
        assertEquals(idempotencyKey(pledge.id, PaymentAction.VOID), call.idempotencyKey)
        val view = success(service.readCampaign(fixture.ref))
        assertEquals(0L, view.counts.authorizedHolds)
        assertEquals(5L, view.capacityLeft)
        assertTrue(view.timeline.any { it.pledgeId == pledge.id && it.type == "VOID_REQUEST" })
        assertTrue(view.timeline.any { it.pledgeId == pledge.id && it.type == "VOID_RESPONSE" })
        assertTrue(view.timeline.any { it.pledgeId == pledge.id && it.detail == "AUTHORIZED -> VOIDED" })
        assertFalse(success(queries.lookupPledge(fixture.workspace.token, pledge.id)).canWithdraw)
    }

    @Test
    fun `withdrawal refuses every non open campaign state without calling the provider`() {
        CampaignState.entries.filter { it != CampaignState.OPEN }.forEach { state ->
            val fixture = fixture(state = state)
            val pledge = authorized(fixture, 1)
            assertEquals(Rejection.INVALID_STATE,
                rejected(service.withdrawPledge(PledgeRef(fixture.ref, pledge.id))).reason)
            assertEquals(PledgeState.AUTHORIZED, pledges.findById(pledge.id).orElseThrow().state)
        }
        assertTrue(gateway.fake.calls.isEmpty())
    }

    @Test
    fun `withdrawal refuses the exact closing instant and non authorized pledges`() {
        val fixture = fixture()
        val pledge = authorized(fixture, 1)
        clock.current = fixture.campaign.closesAt!!
        assertEquals(Rejection.INVALID_STATE,
            rejected(service.withdrawPledge(PledgeRef(fixture.ref, pledge.id))).reason)
        clock.current = start
        PledgeState.entries.filter { it !in setOf(PledgeState.AUTHORIZED, PledgeState.VOIDED) }.forEach { state ->
            pledge.state = state
            pledges.saveAndFlush(pledge)
            assertEquals(Rejection.INVALID_STATE,
                rejected(service.withdrawPledge(PledgeRef(fixture.ref, pledge.id))).reason)
        }
        assertTrue(gateway.fake.calls.isEmpty())
    }

    @Test
    fun `withdrawal and funded batch enforce workspace and pledge ownership`() {
        val owner = fixture(state = CampaignState.FUNDED)
        val other = fixture()
        val pledge = authorized(owner, 1)
        assertEquals(Rejection.NOT_FOUND,
            rejected(service.withdrawPledge(PledgeRef(other.ref, pledge.id))).reason)
        assertEquals(Rejection.NOT_FOUND,
            rejected(service.fundedBatch(CampaignRef(other.workspace.id, owner.campaign.id))).reason)
        assertEquals(Rejection.INVALID_STATE, rejected(service.fundedBatch(other.ref)).reason)
        assertTrue(gateway.fake.calls.isEmpty())
    }

    @Test
    fun `failed withdrawal stays visible without claiming a released hold`() {
        val fixture = fixture()
        val pledge = authorized(fixture, 1)
        gateway.fake.voidAuthorizationResults.add(PaymentResult.Failed(PaymentStatus.FAILED))
        val result = rejected(service.withdrawPledge(PledgeRef(fixture.ref, pledge.id)))
        assertEquals(Rejection.PAYMENT_UNAVAILABLE, result.reason)
        assertEquals(PledgeState.VOID_FAILED, result.pledge?.state)
        val view = success(service.readCampaign(fixture.ref))
        assertEquals(0L, view.counts.authorizedHolds)
        assertEquals(PledgeState.VOID_FAILED, view.needsAttention.single().state)
    }

    @Test
    fun `withdrawal and settlement serialize whichever starts first`() {
        for (withdrawFirst in listOf(true, false)) {
            gateway.reset()
            val fixture = fixture()
            val pledge = authorized(fixture, 1)
            val enteredProvider = CountDownLatch(1)
            val competingRequest = CountDownLatch(1)
            val awaitCompetitor = {
                enteredProvider.countDown()
                assertTrue(competingRequest.await(5, TimeUnit.SECONDS))
            }
            if (withdrawFirst) {
                voidSuccess(pledge)
                gateway.beforeVoid = awaitCompetitor
            } else {
                captureSuccess(pledge)
                gateway.beforeCapture = awaitCompetitor
            }
            val executor = Executors.newFixedThreadPool(2)
            try {
                val first = executor.submit<ServiceResult<*>> {
                    if (withdrawFirst) service.withdrawPledge(PledgeRef(fixture.ref, pledge.id))
                    else service.confirmProduction(fixture.ref)
                }
                assertTrue(enteredProvider.await(5, TimeUnit.SECONDS))
                val second = executor.submit<ServiceResult<*>> {
                    competingRequest.countDown()
                    if (withdrawFirst) service.confirmProduction(fixture.ref)
                    else service.withdrawPledge(PledgeRef(fixture.ref, pledge.id))
                }
                assertIs<ServiceResult.Success<*>>(first.get(10, TimeUnit.SECONDS))
                assertEquals(if (withdrawFirst) Rejection.THRESHOLD_NOT_MET else Rejection.INVALID_STATE,
                    rejected(second.get(10, TimeUnit.SECONDS)).reason)
                assertEquals(if (withdrawFirst) PledgeState.VOIDED else PledgeState.CAPTURED,
                    pledges.findById(pledge.id).orElseThrow().state)
                assertEquals(if (withdrawFirst) 0 else 1,
                    gateway.fake.calls.filterIsInstance<PaymentCall.Capture>().size)
                assertEquals(if (withdrawFirst) 1 else 0,
                    gateway.fake.calls.filterIsInstance<PaymentCall.VoidAuthorization>().size)
            } finally {
                competingRequest.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `authorization stores shipping for the maker batch and excludes incomplete captures from totals`() {
        val fixture = fixture()
        val pledge = created(fixture)
        val address = ShippingAddress("Sample Buyer", "123 Example Road", "Unit 4", "CA", "Example City", "90210", "US")
        gateway.fake.authorizeOrderResults.add(received(AuthorizationDetails(
            AuthorizationId("shipping-authorization"), Money(200, "USD"), start.plusSeconds(86_400),
            orderId = OrderId(pledge.paypalOrderId!!), payerEmail = "buyer@example.test", shippingAddress = address,
        ), PaymentStatus.CREATED))
        success(service.completePledge(PledgeRef(fixture.ref, pledge.id)))
        val saved = pledges.findById(pledge.id).orElseThrow()
        assertEquals(address, saved.shippingAddress)
        val second = authorized(fixture, 2)
        val incomplete = authorized(fixture, 1).apply {
            state = PledgeState.VOIDED
            shippingAddress = ShippingAddress(fullName = "Unpaid Buyer")
        }
        pledges.saveAndFlush(incomplete)
        captureSuccess(saved)
        captureSuccess(second)
        assertEquals(CampaignState.FUNDED, success(service.confirmProduction(fixture.ref)).state)

        val batch = success(service.fundedBatch(fixture.ref))
        assertEquals(3L, batch.totalUnits)
        assertEquals(600L.toBigInteger(), batch.totalAmount)
        assertEquals("USD", batch.currency)
        assertEquals(listOf(pledge.id, second.id), batch.paid.map { it.id })
        assertEquals(address, batch.paid.first().shippingAddress)
        assertEquals("Sample Buyer", batch.paid.first().buyerName)
        assertEquals("b***@example.test", batch.paid.first().payerEmailMasked)
        assertNull(batch.paid.last().shippingAddress)
        assertEquals(PledgeState.CAPTURED, batch.paid.first().state)
        assertEquals(listOf(incomplete.id), batch.incomplete.map { it.id })
        listOf(saved, second).forEach(::healthyCapture)
        assertFalse(success(service.readCampaign(fixture.ref)).toString().contains("Sample Buyer"))
        listOf(saved, second).forEach(::healthyCapture)
        assertFalse(success(queries.lookupPledge(fixture.workspace.token, pledge.id)).toString().contains("Example Road"))
    }

    @Test
    fun `batch snapshot leaves pending payments untouched and sums paid minor units exactly`() {
        val fixture = fixture(state = CampaignState.FUNDED)
        repeat(2) {
            pledges.saveAndFlush(Pledge(fixture.campaign.id, 1, Long.MAX_VALUE, "USD",
                PledgeState.CAPTURED, createdAt = start))
        }
        val pending = authorized(fixture, 1).apply { state = PledgeState.CAPTURE_PENDING }
        pledges.saveAndFlush(pending)
        val batch = success(service.fundedBatch(fixture.ref))
        assertEquals(Long.MAX_VALUE.toBigInteger() * 2.toBigInteger(), batch.totalAmount)
        assertEquals(2L, batch.totalUnits)
        assertEquals(PledgeState.CAPTURE_PENDING, batch.incomplete.single().state)
        assertEquals(pending.id, batch.incomplete.single().id)
        assertTrue(gateway.fake.calls.isEmpty())
    }

    private fun fixture(minimum: Int = 1, capacity: Int = 5, state: CampaignState = CampaignState.OPEN): Fixture {
        val workspace = workspaces.saveAndFlush(Workspace("test-${UUID.randomUUID()}", start))
        ownedWorkspaces.add(workspace.id)
        val option = options.saveAndFlush(Option(workspace.id, "Print", "print", emptyMap(), emptyMap(),
            100, "USD", minimum, capacity, "Two weeks", "US"))
        val wishId = UUID.randomUUID()
        val output = AnalysisOutput(
            wishes = listOf(AnalyzedWish(wishId, WishKind.REQUEST, emptyList())), verdicts = emptyList(),
            proposal = Proposal(option.id, emptyMap(), emptyList()), noProposalReason = null,
            notServed = emptyList(), questionsForMaker = emptyList(), alternatives = emptyList(), optionIds = listOf(option.id),
        )
        val analysis = analyses.saveAndFlush(AnalysisRun(workspace.id, "synthetic", "hash", mapper.writeValueAsString(output), createdAt = start))
        val offer = offers.saveAndFlush(Offer(workspace.id, option.id, analysis.id, "Print", "A5 print", emptyMap(),
            200, "USD", "Shipping included", "US", minimum, capacity, minOf(3, capacity), listOf(wishId)))
        val campaign = campaigns.saveAndFlush(Campaign(offer.id, state,
            openedAt = if (state == CampaignState.DRAFT) null else start,
            closesAt = if (state == CampaignState.DRAFT) null else start.plusSeconds(900),
            confirmDeadline = if (state == CampaignState.DRAFT) null else start.plusSeconds(44_100)))
        return Fixture(workspace, option, analysis, offer, campaign, wishId)
    }

    private fun draft(fixture: Fixture) = DraftOffer(
        fixture.option.id, fixture.analysis.id, "Print", "A5 print", emptyMap(), 200,
        "Shipping included", fixture.offer.minBatch, fixture.offer.capacity, Duration.ofMinutes(15),
        minOf(3, fixture.offer.capacity), listOf(fixture.wishId),
    )

    private fun created(fixture: Fixture): Pledge {
        val id = UUID.randomUUID()
        return pledges.saveAndFlush(Pledge(fixture.campaign.id, 1, 200, "USD",
            paypalOrderId = "order-$id", createdAt = start, id = id))
    }

    private fun authorized(fixture: Fixture, qty: Int): Pledge {
        val id = UUID.randomUUID()
        val sequence = pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(fixture.campaign.id).size
        return pledges.saveAndFlush(Pledge(fixture.campaign.id, qty, 200L * qty, "USD", PledgeState.AUTHORIZED,
            paypalOrderId = "order-$id", authorizationId = "authorization-$id", authorizationExpiresAt = start.plusSeconds(86_400),
            createdAt = start.plusSeconds(sequence.toLong()), id = id))
    }

    private fun captureDetails(pledge: Pledge) = CaptureDetails(CaptureId("capture-${pledge.id}"),
        Money(pledge.amount, pledge.currency), AuthorizationId(pledge.authorizationId!!))
    private fun refundDetails(pledge: Pledge) = RefundDetails(RefundId("refund-${pledge.id}"),
        Money(pledge.amount, pledge.currency), CaptureId("capture-${pledge.id}"))
    private fun healthyAuthorization(pledge: Pledge) {
        gateway.fake.readAuthorizationResults.add(received(AuthorizationDetails(
            AuthorizationId(pledge.authorizationId!!), Money(pledge.amount, pledge.currency), pledge.authorizationExpiresAt,
        ), PaymentStatus.CREATED))
    }
    private fun healthyCapture(pledge: Pledge) {
        gateway.fake.readCaptureResults.add(received(captureDetails(pledge), PaymentStatus.COMPLETED))
    }
    private fun captureSuccess(pledge: Pledge) {
        gateway.fake.captureResults.add(received(captureDetails(pledge), PaymentStatus.COMPLETED))
    }
    private fun refundSuccess(pledge: Pledge) {
        gateway.fake.refundCaptureResults.add(received(refundDetails(pledge), PaymentStatus.COMPLETED))
    }
    private fun voidSuccess(pledge: Pledge) {
        gateway.fake.voidAuthorizationResults.add(received(VoidDetails(AuthorizationId(pledge.authorizationId!!)), PaymentStatus.VOIDED))
    }
    private fun <T> received(resource: T, status: PaymentStatus, debugId: String? = null): PaymentResult<T> =
        PaymentResult.Received(resource, status, debugId)
    private fun <T> success(result: ServiceResult<T>): T = assertIs<ServiceResult.Success<T>>(result).value
    private fun rejected(result: ServiceResult<*>): ServiceResult.Rejected = assertIs<ServiceResult.Rejected>(result)

    private data class Fixture(
        val workspace: Workspace, val option: Option, val analysis: AnalysisRun,
        val offer: Offer, val campaign: Campaign, val wishId: UUID,
    ) {
        val ref get() = CampaignRef(workspace.id, campaign.id)
    }

    @TestConfiguration
    class PaymentTestConfiguration {
        @Bean
        @Primary
        fun testPaymentGateway() = CrashableGateway()

        @Bean
        @Primary
        fun testClock() = MutableClock()
    }
}

class MutableClock : Clock() {
    var current: Instant = Instant.parse("2026-01-01T00:00:00Z")
    override fun instant(): Instant = current
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
}

class SimulatedProcessCrash : RuntimeException("Synthetic process interruption")

class CrashableGateway : PaymentGateway {
    var fake = InMemoryPaymentGateway()
        private set
    var crashAfterCaptureNumber: Int? = null
    var crashAfterRefund = false
    var afterAuthorization: (() -> Unit)? = null
    var beforeCapture: (() -> Unit)? = null
    var beforeVoid: (() -> Unit)? = null
    private var captureNumber = 0

    fun reset() {
        fake = InMemoryPaymentGateway()
        crashAfterCaptureNumber = null
        crashAfterRefund = false
        afterAuthorization = null
        beforeCapture = null
        beforeVoid = null
        captureNumber = 0
    }

    override fun createAuthorizeOrder(request: CreateAuthorizeOrder, idempotencyKey: String) = fake.createAuthorizeOrder(request, idempotencyKey)
    override fun authorizeOrder(orderId: OrderId, idempotencyKey: String): PaymentResult<AuthorizationDetails> {
        val result = fake.authorizeOrder(orderId, idempotencyKey)
        afterAuthorization?.invoke()
        return result
    }
    override fun readAuthorization(authorizationId: AuthorizationId, idempotencyKey: String) = fake.readAuthorization(authorizationId, idempotencyKey)
    override fun capture(authorizationId: AuthorizationId, idempotencyKey: String): PaymentResult<CaptureDetails> {
        beforeCapture?.invoke()
        val result = fake.capture(authorizationId, idempotencyKey)
        captureNumber++
        if (captureNumber == crashAfterCaptureNumber) throw SimulatedProcessCrash()
        return result
    }
    override fun voidAuthorization(authorizationId: AuthorizationId, idempotencyKey: String): PaymentResult<VoidDetails> {
        beforeVoid?.invoke()
        return fake.voidAuthorization(authorizationId, idempotencyKey)
    }
    override fun refundCapture(captureId: CaptureId, idempotencyKey: String): PaymentResult<RefundDetails> {
        val result = fake.refundCapture(captureId, idempotencyKey)
        if (crashAfterRefund) {
            crashAfterRefund = false
            throw SimulatedProcessCrash()
        }
        return result
    }
    override fun readCapture(captureId: CaptureId, idempotencyKey: String) = fake.readCapture(captureId, idempotencyKey)
    override fun readRefund(refundId: RefundId, idempotencyKey: String) = fake.readRefund(refundId, idempotencyKey)
}

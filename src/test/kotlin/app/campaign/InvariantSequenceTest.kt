package app.campaign

import app.campaign.fuzz.SequenceCrash
import app.campaign.fuzz.SequenceGateway
import app.campaign.provenance.DecisionRecordService
import app.campaign.model.*
import app.campaign.port.*
import app.campaign.rules.authorizedUnits
import app.campaign.rules.capturedUnits
import app.campaign.rules.allRequestCount
import app.campaign.rules.PaymentAction
import app.campaign.rules.idempotencyKey
import app.campaign.service.*
import app.campaign.store.*
import app.testing.PaymentCall
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import tools.jackson.databind.ObjectMapper

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(InvariantSequenceTest.Config::class)
class InvariantSequenceTest @Autowired constructor(
    private val service: CampaignService,
    private val workspaces: WorkspaceRepository,
    private val options: OptionRepository,
    private val analyses: AnalysisRunRepository,
    private val offers: OfferRepository,
    private val campaigns: CampaignRepository,
    private val pledges: PledgeRepository,
    private val events: EventRepository,
    private val gateway: SequenceGateway,
    private val clock: SequenceClock,
    private val mapper: ObjectMapper,
    private val records: DecisionRecordService,
) {
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `fixed sequences preserve payment and count invariants`() {
        repeat(16) { seed ->
            gateway.reset()
            clock.current = now
            val workspace = workspaces.saveAndFlush(Workspace("sequence-$seed-${UUID.randomUUID()}", now))
            val option = options.saveAndFlush(Option(workspace.id, "Print", "print", emptyMap(), emptyMap(),
                100, "USD", 1, 20, "Two weeks", "US"))
            val wish = UUID.randomUUID()
            val output = AnalysisOutput(listOf(AnalyzedWish(wish, WishKind.REQUEST, emptyList())), emptyList(),
                Proposal(option.id, emptyMap(), emptyList()), null, emptyList(), emptyList(), emptyList(), listOf(option.id))
            val analysis = analyses.saveAndFlush(AnalysisRun(workspace.id, "synthetic", "hash", mapper.writeValueAsString(output), createdAt = now))
            val offer = offers.saveAndFlush(Offer(workspace.id, option.id, analysis.id, "Print", "A5 print", emptyMap(),
                200, "USD", "Shipping included", "US", 1, 20, 3, listOf(wish)))
            records.recordOffer(offer)
            val campaign = campaigns.saveAndFlush(Campaign(offer.id, CampaignState.OPEN, openedAt = now,
                closesAt = now.plusSeconds(900), confirmDeadline = now.plusSeconds(44100)))
            val ref = CampaignRef(workspace.id, campaign.id)
            val random = Random(seed)
            var actionCount = 0
            fun snapshot(result: ServiceResult<*>? = null) {
                val stored = campaigns.findById(campaign.id).orElseThrow()
                val rows = pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id)
                val authorized = rows.filter { it.state == PledgeState.AUTHORIZED }.sumOf { it.qty.toLong() }
                val paidRows = rows.filter { it.state == PledgeState.CAPTURED }
                val paid = paidRows.sumOf { it.qty.toLong() }
                assertEquals(1, allRequestCount(output.wishes), "seed=$seed")
                assertEquals(authorized, authorizedUnits(rows), "seed=$seed")
                assertEquals(paid, capturedUnits(rows), "seed=$seed")
                val capturedIds = paidRows.mapNotNull { it.captureId }.toSet()
                assertTrue(gateway.paidCaptures.containsAll(capturedIds), "seed=$seed provider paid captures")
                if (stored.state != CampaignState.SETTLING) {
                    assertEquals(gateway.paidCaptures, capturedIds, "seed=$seed provider paid captures")
                }
                val view = (result as? ServiceResult.Success<*>)?.value as? CampaignView
                if (view != null) {
                    assertEquals(CampaignCounts(0, authorized, paid), view.counts, "seed=$seed")
                }
                assertTrue(gateway.captureEffects.values.all { it <= 1 }, "seed=$seed duplicate capture effect")
                assertTrue(gateway.refundEffects.values.all { it <= 1 }, "seed=$seed duplicate refund effect")
                for (row in rows) {
                    val captures = gateway.calls.filterIsInstance<PaymentCall.Capture>().filter { it.authorizationId.value == row.authorizationId }
                    val refunds = gateway.calls.filterIsInstance<PaymentCall.RefundCapture>().filter { it.captureId.value == row.captureId }
                    assertTrue(captures.all { it.idempotencyKey == idempotencyKey(row.id, PaymentAction.CAPTURE) }, "seed=$seed")
                    assertTrue(refunds.all { it.idempotencyKey == idempotencyKey(row.id, PaymentAction.REFUND) }, "seed=$seed")
                    if (stored.state in setOf(CampaignState.FUNDED, CampaignState.NOT_FUNDED)) {
                        assertTrue(row.state != PledgeState.AUTHORIZED, "seed=$seed terminal hold")
                        if (row.authorizationId in gateway.activeHolds) {
                            assertTrue(row.state in setOf(PledgeState.VOID_FAILED, PledgeState.CAPTURE_PENDING), "seed=$seed unresolved hold")
                        }
                    }
                }
                if (stored.state in setOf(CampaignState.FUNDED, CampaignState.NOT_FUNDED)) {
                    val attention = rows.any { it.state in setOf(PledgeState.CAPTURE_PENDING, PledgeState.VOID_FAILED,
                        PledgeState.REFUND_PENDING, PledgeState.REFUND_FAILED) }
                    assertEquals(attention, stored.needsAttention, "seed=$seed unresolved payment")
                }
            }
            fun step(action: () -> ServiceResult<*>): ServiceResult<*> {
                val result = action()
                actionCount++
                snapshot(result)
                return result
            }
            fun start(): PledgeView = assertIs<ServiceResult.Success<PledgeView>>(step { service.startPledge(ref, 1) }).value
            fun randomAction() {
                val rows = pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id)
                val row = rows.randomOrNull(random)
                when (random.nextInt(4)) {
                    0 -> if (rows.size < 8) step { service.startPledge(ref, 1) } else step { service.reconcile(ref) }
                    1 -> if (row != null) step { service.completePledge(PledgeRef(ref, row.id)) } else step { service.reconcile(ref) }
                    2 -> if (row != null) step { service.withdrawPledge(PledgeRef(ref, row.id)) } else step { service.reconcile(ref) }
                    else -> step { service.reconcile(ref) }
                }
            }
            try {
                val first = start()
                step { service.completePledge(PledgeRef(ref, first.id)) }
                start()
                repeat(random.nextInt(40, 49)) { randomAction() }
                val created = start()
                step { service.completePledge(PledgeRef(ref, created.id)) }
                start()
                val scenario = listOf(0, 1, 2, 3).shuffled(Random(42))[seed % 4]
                when (scenario) {
                    0 -> { gateway.cleanupFails = true; step { service.cancel(ref) } }
                    1 -> { gateway.captureStatus = PaymentStatus.DECLINED; gateway.cleanupFails = true; step { service.confirmProduction(ref) } }
                    2 -> { gateway.captureStatus = PaymentStatus.PENDING; step { service.confirmProduction(ref) } }
                    else -> {
                        gateway.crashCapture = true
                        var crashed = false
                        try { service.confirmProduction(ref) } catch (_: SequenceCrash) { crashed = true }
                        actionCount++
                        assertTrue(crashed, "seed=$seed expected capture interruption")
                        assertEquals(CampaignState.SETTLING, campaigns.findById(campaign.id).orElseThrow().state)
                        snapshot()
                    }
                }
                val interrupted = pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id)
                if (scenario == 0 || scenario == 1) {
                    assertTrue(interrupted.any { it.state == PledgeState.VOID_FAILED }, "seed=$seed failed cleanup")
                }
                if (scenario == 2) {
                    assertTrue(interrupted.any { it.state == PledgeState.CAPTURE_PENDING }, "seed=$seed pending capture")
                }
                val recoveryAt = actionCount + random.nextInt(1, 4)
                while (actionCount < 64) {
                    // Allow each retry to pass the provider backoff cap without sleeping.
                    clock.current = clock.current.plusSeconds(3601)
                    if (actionCount == recoveryAt) {
                        gateway.cleanupFails = false
                        gateway.pendingCompletes = true
                    }
                    when (random.nextInt(3)) {
                        0 -> step { service.reconcile(ref) }
                        1 -> step { service.confirmProduction(ref) }
                        else -> step { service.cancel(ref) }
                    }
                }
                val final = campaigns.findById(campaign.id).orElseThrow()
                val finalRows = pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id)
                assertEquals(64, actionCount)
                assertTrue(finalRows.any { it.state == PledgeState.ABANDONED }, "seed=$seed abandoned order")
                assertTrue(!final.needsAttention, "seed=$seed cleanup after recovery")
                assertTrue(gateway.activeHolds.isEmpty(), "seed=$seed provider holds after recovery")
                assertTrue(finalRows.none { it.state in setOf(PledgeState.AUTHORIZED, PledgeState.CAPTURE_PENDING,
                    PledgeState.VOID_FAILED, PledgeState.REFUND_PENDING, PledgeState.REFUND_FAILED) }, "seed=$seed")
            } finally {
                events.deleteAll(events.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id))
                pledges.deleteAll(pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id))
                campaigns.deleteById(campaign.id)
                offers.deleteById(offer.id)
                analyses.deleteById(analysis.id)
                options.deleteById(option.id)
                workspaces.deleteById(workspace.id)
            }
        }
    }

    @Test
    fun `provider fake deduplicates replays and counts effects by resource`() {
        val provider = SequenceGateway()
        val order = assertIs<PaymentResult.Received<OrderDetails>>(
            provider.createAuthorizeOrder(CreateAuthorizeOrder(UUID.randomUUID(), Money(200, "USD")), "create"),
        ).resource.orderId
        val authorization = assertIs<PaymentResult.Received<AuthorizationDetails>>(
            provider.authorizeOrder(order, "authorize"),
        ).resource.authorizationId
        assertEquals(PaymentStatus.CREATED, provider.readAuthorization(authorization, "read-auth").status)
        val capture = assertIs<PaymentResult.Received<CaptureDetails>>(provider.capture(authorization, "capture"))
        assertEquals(PaymentStatus.CAPTURED, provider.readAuthorization(authorization, "read-auth").status)
        assertEquals(PaymentStatus.COMPLETED, provider.readCapture(capture.resource.captureId, "read-capture").status)
        assertEquals(capture, provider.capture(authorization, "capture"))
        val refund = assertIs<PaymentResult.Received<RefundDetails>>(provider.refundCapture(capture.resource.captureId, "refund"))
        assertEquals(refund, provider.refundCapture(capture.resource.captureId, "refund"))
        assertEquals(PaymentStatus.REFUNDED, provider.readCapture(capture.resource.captureId, "read-capture").status)
        assertEquals(1, provider.captureEffects[authorization.value])
        assertEquals(1, provider.refundEffects[capture.resource.captureId.value])
        provider.refundCapture(capture.resource.captureId, "different-refund")
        assertEquals(2, provider.refundEffects[capture.resource.captureId.value])
        provider.capture(authorization, "different-capture")
        assertEquals(2, provider.captureEffects[authorization.value])
    }

    @TestConfiguration
    class Config {
        @Bean @Primary fun sequenceGateway() = SequenceGateway()
        @Bean @Primary fun sequenceClock() = SequenceClock()
    }
}

class SequenceClock : Clock() {
    var current: Instant = Instant.parse("2026-01-01T00:00:00Z")
    override fun instant(): Instant = current
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
}

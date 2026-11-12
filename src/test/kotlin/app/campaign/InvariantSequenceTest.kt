package app.campaign

import app.campaign.fuzz.SequenceCrash
import app.campaign.fuzz.SequenceGateway
import app.campaign.model.*
import app.campaign.port.*
import app.campaign.rules.PaymentAction
import app.campaign.rules.idempotencyKey
import app.campaign.service.*
import app.campaign.store.*
import app.testing.PaymentCall
import java.time.Clock
import java.time.Instant
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
    private val mapper: ObjectMapper,
) {
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `fixed sequences preserve payment and count invariants`() {
        repeat(8) { seed ->
            gateway.reset()
            val workspace = workspaces.saveAndFlush(Workspace("sequence-$seed-${UUID.randomUUID()}", now))
            val option = options.saveAndFlush(Option(workspace.id, "Print", "print", emptyMap(), emptyMap(),
                100, "USD", 1, 20, "Two weeks", "US"))
            val wish = UUID.randomUUID()
            val output = AnalysisOutput(listOf(AnalyzedWish(wish, WishKind.REQUEST, emptyList())), emptyList(),
                Proposal(option.id, emptyMap(), emptyList()), null, emptyList(), emptyList(), emptyList(), listOf(option.id))
            val analysis = analyses.saveAndFlush(AnalysisRun(workspace.id, "synthetic", "hash", mapper.writeValueAsString(output), createdAt = now))
            val offer = offers.saveAndFlush(Offer(workspace.id, option.id, analysis.id, "Print", "A5 print", emptyMap(),
                200, "USD", "Shipping included", "US", 1, 20, 3, listOf(wish)))
            val campaign = campaigns.saveAndFlush(Campaign(offer.id, CampaignState.OPEN, openedAt = now,
                closesAt = now.plusSeconds(900), confirmDeadline = now.plusSeconds(44100)))
            val ref = CampaignRef(workspace.id, campaign.id)
            val random = Random(seed)
            fun snapshot() {
                val view = assertIs<ServiceResult.Success<CampaignView>>(service.readCampaign(ref)).value
                val rows = pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id)
                assertEquals(1, view.counts.statedInterest, "seed=$seed")
                assertEquals(rows.filter { it.state == PledgeState.AUTHORIZED }.sumOf { it.qty.toLong() }, view.counts.authorizedHolds, "seed=$seed")
                assertEquals(rows.filter { it.state == PledgeState.CAPTURED }.sumOf { it.qty.toLong() }, view.counts.paid, "seed=$seed")
                assertEquals(gateway.paidCaptures, rows.filter { it.state == PledgeState.CAPTURED }.mapNotNull { it.captureId }.toSet(), "seed=$seed provider paid captures")
                for (row in rows) {
                    val captures = gateway.calls.filterIsInstance<PaymentCall.Capture>().filter { it.authorizationId.value == row.authorizationId }
                    val refunds = gateway.calls.filterIsInstance<PaymentCall.RefundCapture>().filter { it.captureId.value == row.captureId }
                    assertTrue(captures.all { it.idempotencyKey == idempotencyKey(row.id, PaymentAction.CAPTURE) }, "seed=$seed")
                    assertTrue(refunds.all { it.idempotencyKey == idempotencyKey(row.id, PaymentAction.REFUND) }, "seed=$seed")
                    if (row.state == PledgeState.CAPTURED) assertTrue(row.captureId != null && captures.isNotEmpty(), "seed=$seed")
                    if (view.state in setOf(CampaignState.FUNDED, CampaignState.NOT_FUNDED)) {
                        assertTrue(row.state != PledgeState.AUTHORIZED, "seed=$seed terminal hold")
                        if (row.state in setOf(PledgeState.CAPTURE_PENDING, PledgeState.VOID_FAILED, PledgeState.REFUND_PENDING, PledgeState.REFUND_FAILED)) {
                            assertTrue(view.needsAttention.any { it.pledgeId == row.id }, "seed=$seed unresolved payment")
                        }
                    }
                }
            }
            try {
                repeat(24) {
                    val rows = pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id)
                    when (random.nextInt(4)) {
                        0 -> service.startPledge(ref, random.nextInt(1, 4))
                        1 -> rows.randomOrNull(random)?.let { service.completePledge(PledgeRef(ref, it.id)) }
                        2 -> rows.randomOrNull(random)?.let { service.withdrawPledge(PledgeRef(ref, it.id)) }
                        else -> service.reconcile(ref)
                    }
                    snapshot()
                }
                // Ensure the settlement scenarios have an approved pledge as well as any abandoned orders.
                val created = assertIs<ServiceResult.Success<PledgeView>>(service.startPledge(ref, 1)).value
                snapshot()
                service.completePledge(PledgeRef(ref, created.id))
                snapshot()
                service.startPledge(ref, 1)
                snapshot()
                when (seed % 4) {
                    0 -> { gateway.cleanupFails = true; service.cancel(ref) }
                    1 -> { gateway.captureStatus = PaymentStatus.DECLINED; gateway.cleanupFails = true; service.confirmProduction(ref) }
                    2 -> { gateway.captureStatus = PaymentStatus.PENDING; service.confirmProduction(ref) }
                    else -> {
                        gateway.crashCapture = true
                        var crashed = false
                        try { service.confirmProduction(ref) } catch (_: SequenceCrash) { crashed = true }
                        assertTrue(crashed, "seed=$seed expected capture interruption")
                        assertEquals(CampaignState.SETTLING, campaigns.findById(campaign.id).orElseThrow().state)
                    }
                }
                snapshot()
                val interrupted = assertIs<ServiceResult.Success<CampaignView>>(service.readCampaign(ref)).value
                assertTrue(interrupted.pledges.any { it.state == PledgeState.ABANDONED }, "seed=$seed abandoned order")
                if (seed % 4 == 0 || seed % 4 == 1) {
                    assertTrue(interrupted.needsAttention.any { it.state == PledgeState.VOID_FAILED }, "seed=$seed failed cleanup")
                }
                if (seed % 4 == 2) {
                    assertTrue(interrupted.needsAttention.any { it.state == PledgeState.CAPTURE_PENDING }, "seed=$seed pending capture")
                }
                gateway.cleanupFails = false
                gateway.pendingCompletes = true
                repeat(4) { service.reconcile(ref); snapshot() }
                val final = assertIs<ServiceResult.Success<CampaignView>>(service.readCampaign(ref)).value
                assertTrue(final.needsAttention.isEmpty(), "seed=$seed cleanup after recovery")
                assertTrue(final.pledges.none { it.state in setOf(PledgeState.AUTHORIZED, PledgeState.CAPTURE_PENDING,
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

    @TestConfiguration
    class Config {
        @Bean @Primary fun sequenceGateway() = SequenceGateway()
        @Bean @Primary fun sequenceClock(): Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    }
}

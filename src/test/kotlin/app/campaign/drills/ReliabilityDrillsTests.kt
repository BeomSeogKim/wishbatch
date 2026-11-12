package app.campaign.drills

import app.campaign.books.BooksMatch
import app.campaign.books.BooksResource
import app.campaign.books.BooksResourceKind
import app.campaign.model.CampaignReason
import app.campaign.model.CampaignState
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.port.PaymentStatus
import app.campaign.rules.CAMPAIGN_TRANSITIONS
import app.campaign.rules.CampaignTransition
import app.campaign.rules.PLEDGE_TRANSITIONS
import app.campaign.rules.PaymentAction
import app.campaign.rules.PledgeTransition
import app.campaign.rules.idempotencyKey
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReliabilityDrillsTests {
    @Test
    fun `all four fixed scripts use legal transition edges and completed capture counts`() {
        val results = ReliabilityDrills.play()
        assertEquals(4, results.size)
        results.forEach { result ->
            val snapshots = listOf(result.before) + result.steps.map { it.snapshot } + result.after
            snapshots.zipWithNext().forEach { (before, after) ->
                if (before.campaignState != after.campaignState) {
                    assertTrue(CampaignTransition(before.campaignState, after.campaignState, after.reason) in CAMPAIGN_TRANSITIONS)
                }
                before.pledgeStates.forEach { (id, state) ->
                    val next = after.pledgeStates.getValue(id)
                    if (state != next) assertTrue(PledgeTransition(state, next) in PLEDGE_TRANSITIONS)
                }
            }
            snapshots.forEach { snapshot ->
                assertEquals(4, snapshot.counts.allRequests)
                assertEquals(snapshot.pledgeStates.values.count { it == PledgeState.AUTHORIZED }.toLong(), snapshot.counts.authorizedHolds)
                assertEquals(snapshot.pledgeStates.values.count { it == PledgeState.CAPTURED }.toLong(), snapshot.counts.paid)
            }
        }
    }

    @Test
    fun `fresh playback is deterministic and every successful original key has one simulated effect`() {
        val first = ReliabilityDrills.play()
        assertEquals(first, ReliabilityDrills.play())
        assertTrue(first.flatMap { it.writeEffects.values }.isNotEmpty())
        first.forEach { result ->
            assertTrue(result.writeEffects.values.all { it == 1 })
            val effectiveWrites = result.steps.filter { it.effective && it.endpoint?.startsWith("POST ") == true }
            assertEquals(result.writeEffects.keys, effectiveWrites.map { requireNotNull(it.key) }.toSet())
            assertEquals(effectiveWrites.size, effectiveWrites.map { it.key }.distinct().size)
        }
    }

    @Test
    fun `declined capture releases remaining hold and refunds completed capture after shortfall`() {
        val result = drill("capture-declined")
        assertEquals(CampaignState.NOT_FUNDED, result.after.campaignState)
        assertEquals(CampaignReason.CAPTURE_SHORTFALL, result.after.reason)
        assertEquals(setOf(PledgeState.VOIDED, PledgeState.REFUNDED), result.after.pledgeStates.values.toSet())
        assertEquals(0L, result.after.counts.paid)
        assertEquals(0L, result.after.counts.authorizedHolds)
        assertTrue(result.steps.any { it.status == "DECLINED" && !it.effective })
        assertTrue(result.steps.any { it.status == "PENDING" && it.snapshot.needsAttention.isNotEmpty() })
        assertTrue(result.after.needsAttention.isEmpty())
        assertTrue(result.books.all { it.after.match == BooksMatch.MATCH })
        val refunds = result.steps.filter { it.endpoint?.endsWith("/refund") == true }
        assertEquals(2, refunds.size)
        assertEquals(1, refunds.map { it.key }.distinct().size)
        assertEquals(1, refunds.count { it.effective })
    }

    @Test
    fun `timeout stays unpaid until later evidence and uses original capture key without duplicate effect`() {
        val result = drill("capture-timeout")
        val ambiguous = result.steps.single { it.status == "TIMEOUT" }
        assertEquals(0L, ambiguous.snapshot.counts.paid)
        assertEquals(PledgeState.CAPTURE_PENDING, ambiguous.snapshot.pledgeStates.values.single())
        assertTrue(ambiguous.snapshot.needsAttention.isNotEmpty())
        val captureRequests = result.steps.filter { it.endpoint?.endsWith("/capture") == true }
        assertEquals(2, captureRequests.size)
        val key = idempotencyKey(result.books.single().pledgeId, PaymentAction.CAPTURE)
        assertTrue(captureRequests.all { it.key == key })
        assertEquals(1, captureRequests.count { it.effective })
        assertEquals(1, result.writeEffects[key])
        val readIndex = result.steps.indexOfFirst { it.operation == "Later simulated provider read" }
        val replayIndex = result.steps.indexOfFirst { it.operation == "Replay original capture key" }
        assertTrue(readIndex < replayIndex)
        assertEquals(CampaignState.NOT_FUNDED, result.after.campaignState)
        assertEquals(PledgeState.REFUNDED, result.books.single().after.state)
        assertEquals(BooksMatch.MATCH, result.books.single().after.match)
    }

    @Test
    fun `duplicate synthetic event is processed once and provider read overrules event body`() {
        val result = drill("duplicate-webhook")
        val deliveries = result.steps.filter { it.operation == "Synthetic webhook delivery" }
        assertEquals(2, deliveries.size)
        assertEquals(1, deliveries.map { it.providerId }.distinct().size)
        assertTrue(deliveries.all { "claims DECLINED" in it.detail })
        assertEquals(listOf("ACCEPTED", "DUPLICATE"), deliveries.map { it.status })
        assertEquals(1, result.webhookProcessings)
        assertEquals(1, result.steps.count { it.operation == "Re-read simulated provider" && it.status == "COMPLETED" })
        assertEquals(PledgeState.REFUNDED, result.books.single().after.state)
        assertEquals(BooksMatch.MATCH, result.books.single().after.match)
    }

    @Test
    fun `unavailable pledge has unknown evidence and no authorized paid or write demand`() {
        val result = drill("pledge-unavailable")
        assertEquals(BooksMatch.UNKNOWN, result.books.single().before.match)
        assertEquals(BooksMatch.UNKNOWN, result.books.single().after.match)
        assertTrue(result.books.single().after.resources.all { it.status == null && it.amount == null && it.currency == null })
        assertEquals(0L, result.after.counts.authorizedHolds)
        assertEquals(0L, result.after.counts.paid)
        assertEquals(PledgeState.CREATED, result.after.pledgeStates.values.single())
        assertTrue(result.writeEffects.isEmpty())
        assertTrue(result.after.needsAttention.isNotEmpty())
    }

    @Test
    fun `missing or mismatched synthetic amount currency identity or resource evidence cannot match`() {
        val pledge = Pledge(UUID(0, 1), 1, 1200, "USD", PledgeState.AUTHORIZED,
            paypalOrderId = "sim-order", authorizationId = "sim-authorization", createdAt = Instant.EPOCH)
        val order = BooksResource(BooksResourceKind.ORDER, "sim-order", PaymentStatus.COMPLETED, "COMPLETED", 1200, "USD", true)
        val auth = BooksResource(BooksResourceKind.AUTHORIZATION, "sim-authorization", PaymentStatus.CREATED, "CREATED", 1200, "USD", true)
        fun compare(resources: List<BooksResource>) = compareSimulatedBooks(pledge, resources).match
        assertEquals(BooksMatch.MATCH, compare(listOf(order, auth)))
        listOf(auth.copy(status = null), auth.copy(amount = null), auth.copy(currency = null)).forEach {
            assertEquals(BooksMatch.UNKNOWN, compare(listOf(order, it)))
        }
        assertEquals(BooksMatch.UNKNOWN, compare(listOf(order)))
        listOf(auth.copy(amount = 1201), auth.copy(currency = "EUR"), auth.copy(id = "sim-foreign"),
            auth.copy(status = PaymentStatus.VOIDED), auth.copy(evidenceMatches = false)).forEach {
            assertEquals(BooksMatch.MISMATCH, compare(listOf(order, it)))
        }
    }

    @Test
    fun `all provider identifiers and timeline times are visibly simulated`() {
        ReliabilityDrills.play().forEach { result ->
            result.books.forEach { row ->
                (row.before.resources + row.after.resources).forEach { assertTrue(it.id.startsWith("sim-")) }
                assertEquals(1200L, row.amount)
                assertEquals("USD", row.currency)
            }
            result.steps.forEach { step ->
                assertTrue(step.timeLabel.startsWith("Simulated T+"))
                step.providerId?.let { assertTrue(it.startsWith("sim-")) }
                assertFalse(step.snapshot.pledgeStates.values.any { it == PledgeState.PARTIALLY_REFUNDED })
            }
        }
    }

    private fun drill(id: String) = ReliabilityDrills.play().single { it.id == id }
}

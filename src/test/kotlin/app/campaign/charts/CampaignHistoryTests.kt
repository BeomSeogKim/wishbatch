package app.campaign.charts

import app.campaign.model.Event
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CampaignHistoryTests {
    private val campaign = UUID.randomUUID()
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private fun pledge(state: PledgeState = PledgeState.CAPTURED, qty: Int = 3) =
        Pledge(campaign, qty, 1500, "USD", state, createdAt = start)
    private fun created(pledge: Pledge, second: Long = 0) =
        Event(campaign, "PLEDGE_CREATED", pledge.id, detail = "CREATED", createdAt = start.plusSeconds(second))
    private fun edge(pledge: Pledge, detail: String, second: Long) =
        Event(campaign, "PLEDGE_STATE", pledge.id, detail = detail, createdAt = start.plusSeconds(second))
    private fun history(pledge: Pledge, vararg events: Event) = campaignCountHistory(campaign, listOf(pledge), events.toList())

    @Test
    fun `authorization capture pending and timeout recovery move units without double counting`() {
        val pledge = pledge()
        val captured = edge(pledge, "CAPTURE_PENDING -> CAPTURED", 4)
        val points = history(pledge, created(pledge), edge(pledge, "CREATED -> AUTHORIZED", 1),
            edge(pledge, "AUTHORIZED -> CAPTURE_FAILED", 2), edge(pledge, "CAPTURE_FAILED -> CAPTURE_PENDING", 3),
            captured, captured).checkpoints
        assertEquals(listOf(0L, 3L, 0L, 0L, 0L), points.map { it.authorized })
        assertEquals(listOf(0L, 0L, 0L, 0L, 3L), points.map { it.paid })
        assertTrue(points.all { it.interest == null && it.gap == null })
    }

    @Test
    fun `refund failures and repeated retry edges never restore fully paid units`() {
        val pledge = pledge(PledgeState.REFUNDED)
        val transitions = listOf("CREATED -> AUTHORIZED", "AUTHORIZED -> CAPTURED", "CAPTURED -> REFUND_PENDING",
            "REFUND_PENDING -> REFUND_FAILED", "REFUND_FAILED -> REFUND_PENDING", "REFUND_PENDING -> REFUND_FAILED",
            "REFUND_FAILED -> REFUND_PENDING", "REFUND_PENDING -> REFUNDED")
        val result = campaignCountHistory(campaign, listOf(pledge), listOf(created(pledge)) + transitions.mapIndexed { i, detail ->
            edge(pledge, detail, i.toLong() + 1)
        })
        assertEquals(listOf(0L, 0L, 3L, 0L, 0L, 0L, 0L, 0L, 0L), result.checkpoints.map { it.paid })
        assertTrue(result.limits.isEmpty())
    }

    @Test
    fun `partial refund excludes the entire pledge without computing a remainder`() {
        val pledge = pledge(PledgeState.PARTIALLY_REFUNDED)
        val result = history(pledge, created(pledge), edge(pledge, "CREATED -> AUTHORIZED", 1),
            edge(pledge, "AUTHORIZED -> CAPTURED", 2), edge(pledge, "CAPTURED -> PARTIALLY_REFUNDED", 3))
        assertEquals(listOf(0L, 0L, 3L, 0L), result.checkpoints.map { it.paid })
        assertTrue(result.limits.isEmpty())
    }

    @Test
    fun `request and repeated provider response events do not create count points`() {
        val pledge = pledge(PledgeState.AUTHORIZED)
        val result = history(pledge, created(pledge), edge(pledge, "CREATED -> AUTHORIZED", 1),
            Event(campaign, "CAPTURE_RESPONSE", pledge.id, paypalStatus = "COMPLETED", createdAt = start.plusSeconds(2)),
            Event(campaign, "CAPTURE_RESPONSE", pledge.id, paypalStatus = "COMPLETED", createdAt = start.plusSeconds(3)))
        assertEquals(2, result.checkpoints.size)
        assertEquals(3L, result.checkpoints.last().authorized)
        assertEquals(0L, result.checkpoints.last().paid)
    }

    @Test
    fun `input order is sorted by recorded time but contradictory old states create a persistent gap`() {
        val pledge = pledge()
        val good = listOf(created(pledge), edge(pledge, "CREATED -> AUTHORIZED", 1), edge(pledge, "AUTHORIZED -> CAPTURED", 2))
        assertEquals(listOf(0L, 0L, 3L), campaignCountHistory(campaign, listOf(pledge), good.reversed()).checkpoints.map { it.paid })
        val bad = history(pledge, created(pledge), edge(pledge, "AUTHORIZED -> CAPTURED", 1), edge(pledge, "CREATED -> AUTHORIZED", 2))
        assertTrue(bad.checkpoints.all { it.paid == null && it.authorized == null && it.gap != null })
        val stale = history(pledge, *good.toTypedArray(), edge(pledge, "AUTHORIZED -> CAPTURED", 3))
        assertNull(stale.checkpoints.last().paid)
        assertNotNull(stale.checkpoints.last().gap)
    }

    @Test
    fun `missing creation missing final transitions and malformed details never fabricate counts`() {
        val pledge = pledge()
        val missing = history(pledge, edge(pledge, "AUTHORIZED -> CAPTURED", 1))
        assertNull(missing.checkpoints.single().paid)
        val missingFinal = history(pledge, created(pledge), edge(pledge, "CREATED -> AUTHORIZED", 1))
        assertTrue(missingFinal.checkpoints.all { it.paid == null && it.gap != null })
        for (detail in listOf("bad <script>", "CREATED -> CAPTURED", "CREATED -> AUTHORIZED -> CAPTURED")) {
            assertNotNull(history(pledge, created(pledge), edge(pledge, detail, 1)).checkpoints.last().gap)
        }
        assertTrue(history(pledge).checkpoints.isEmpty())
        assertTrue(history(pledge).limits.isNotEmpty())
    }

    @Test
    fun `equal time independent pledges share one point but dependent changes are ambiguous`() {
        val first = pledge(PledgeState.AUTHORIZED, 2)
        val second = pledge(PledgeState.AUTHORIZED, 3)
        val result = campaignCountHistory(campaign, listOf(first, second), listOf(created(first), created(second),
            edge(first, "CREATED -> AUTHORIZED", 1), edge(second, "CREATED -> AUTHORIZED", 1)))
        assertEquals(listOf(0L, 5L), result.checkpoints.map { it.authorized })
        assertEquals(2, result.checkpoints.last().sources.size)
        val ambiguous = history(first, created(first), edge(first, "CREATED -> AUTHORIZED", 0))
        assertNull(ambiguous.checkpoints.single().authorized)
        assertNotNull(ambiguous.checkpoints.single().gap)
    }

    @Test
    fun `foreign pledge references invalid quantities and precreation times are gaps`() {
        val pledge = pledge(PledgeState.CREATED)
        val foreign = Event(campaign, "PLEDGE_CREATED", UUID.randomUUID(), detail = "CREATED", createdAt = start)
        assertNotNull(history(pledge, created(pledge), foreign).checkpoints.single().gap)
        assertNotNull(history(pledge, created(pledge, -1)).checkpoints.single().gap)
        val invalid = pledge(PledgeState.CREATED, 0)
        assertNotNull(history(invalid, created(invalid)).checkpoints.single().gap)
    }

    @Test
    fun `publication supplies a single interest anchor with no payment backfill`() {
        val publication = Event(campaign, "CAMPAIGN_STATE", detail = "DRAFT -> OPEN", createdAt = start)
        val result = campaignCountHistory(campaign, emptyList(), listOf(publication), publication, 12)
        assertEquals(12, result.checkpoints.single().interest)
        assertNull(result.checkpoints.single().paid)
        assertNull(result.checkpoints.single().authorized)
        assertTrue(campaignCountHistory(campaign, emptyList(), emptyList()).checkpoints.isEmpty())
    }

    @Test
    fun `a missing transition invalidates aggregates between another pledges checkpoints`() {
        val first = pledge(PledgeState.CAPTURED)
        val second = pledge(PledgeState.AUTHORIZED)
        val result = campaignCountHistory(campaign, listOf(first, second), listOf(created(first), created(second),
            edge(second, "CREATED -> AUTHORIZED", 2), edge(first, "AUTHORIZED -> CAPTURED", 3)))
        assertTrue(result.checkpoints.all { it.paid == null && it.authorized == null && it.gap != null })
    }

    @Test
    fun `large immutable quantities use long totals and voided holds leave zero units`() {
        val first = pledge(PledgeState.AUTHORIZED, Int.MAX_VALUE)
        val second = pledge(PledgeState.VOIDED, Int.MAX_VALUE)
        val result = campaignCountHistory(campaign, listOf(first, second), listOf(created(first), created(second),
            edge(first, "CREATED -> AUTHORIZED", 1), edge(second, "CREATED -> AUTHORIZED", 1),
            edge(second, "AUTHORIZED -> VOIDED", 2)))
        assertEquals(listOf(0L, 4294967294L, 2147483647L), result.checkpoints.map { it.authorized })
        assertTrue(result.checkpoints.all { it.paid == 0L })
    }
}

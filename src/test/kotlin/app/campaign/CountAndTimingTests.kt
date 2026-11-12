package app.campaign

import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.model.WishKind
import app.campaign.port.AnalyzedWish
import app.campaign.rules.ALLOWED_CAMPAIGN_DURATIONS
import app.campaign.rules.PaymentAction
import app.campaign.rules.authorizedUnits
import app.campaign.rules.capturedUnits
import app.campaign.rules.closesAt
import app.campaign.rules.confirmDeadline
import app.campaign.rules.funded
import app.campaign.rules.idempotencyKey
import app.campaign.rules.allRequestCount
import app.campaign.rules.thresholdMet
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CountAndTimingTests {
    private val openedAt = Instant.parse("2026-01-01T12:00:00Z")

    @Test
    fun `interest counts requests once regardless of requested quantity`() {
        val request = AnalyzedWish(UUID.randomUUID(), WishKind.REQUEST, emptyList(), quantity = 99)
        val noise = AnalyzedWish(UUID.randomUUID(), WishKind.NOT_A_REQUEST, emptyList())
        assertEquals(1, allRequestCount(listOf(request, request, noise)))
        assertEquals(0, allRequestCount(emptyList()))
    }

    @Test
    fun `only authorized and captured quantities contribute to their respective counts`() {
        for (state in PledgeState.entries) {
            val pledges = listOf(pledge(state, 3))
            assertEquals(if (state == PledgeState.AUTHORIZED) 3L else 0L, authorizedUnits(pledges), state.name)
            assertEquals(if (state == PledgeState.CAPTURED) 3L else 0L, capturedUnits(pledges), state.name)
        }
        val mixed = listOf(pledge(PledgeState.AUTHORIZED, 2), pledge(PledgeState.AUTHORIZED, 3), pledge(PledgeState.CAPTURED, 4))
        assertEquals(5L, authorizedUnits(mixed))
        assertEquals(4L, capturedUnits(mixed))
        assertEquals(0L, authorizedUnits(emptyList()))
        assertEquals(0L, capturedUnits(emptyList()))
        for (state in listOf(PledgeState.AUTHORIZED, PledgeState.CAPTURED)) {
            val large = listOf(pledge(state, Int.MAX_VALUE), pledge(state, Int.MAX_VALUE))
            val total = if (state == PledgeState.AUTHORIZED) authorizedUnits(large) else capturedUnits(large)
            assertEquals(4_294_967_294L, total)
        }
    }

    @Test
    fun `threshold and funding include equality but remain separate facts`() {
        for (count in listOf(0L, 2L, 3L, 4L, Long.MAX_VALUE)) {
            assertEquals(count >= 3, thresholdMet(count, 3))
            assertEquals(count >= 3, funded(count, 3))
        }
        val pledges = listOf(pledge(PledgeState.AUTHORIZED, 3), pledge(PledgeState.CAPTURE_PENDING, 3))
        assertTrue(thresholdMet(authorizedUnits(pledges), 3))
        assertFalse(funded(capturedUnits(pledges), 3))
        for (minBatch in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException> { thresholdMet(3, minBatch) }
            assertFailsWith<IllegalArgumentException> { funded(3, minBatch) }
        }
        assertFailsWith<IllegalArgumentException> { thresholdMet(-1, 3) }
        assertFailsWith<IllegalArgumentException> { funded(-1, 3) }
    }

    @Test
    fun `all supported windows close exactly and leave twelve hours to confirm`() {
        val durations = setOf(Duration.ofMinutes(15), Duration.ofHours(2), Duration.ofHours(24), Duration.ofHours(48))
        assertEquals(durations, ALLOWED_CAMPAIGN_DURATIONS)
        for (duration in durations) {
            val close = closesAt(openedAt, duration)
            assertEquals(openedAt.plus(duration), close)
            assertEquals(close.plusSeconds(43_200), confirmDeadline(close))
            assertTrue(Duration.between(openedAt, confirmDeadline(close)) <= Duration.ofHours(60))
        }
        assertEquals(Instant.parse("2026-01-04T00:00:00Z"), confirmDeadline(closesAt(openedAt, Duration.ofHours(48))))
    }

    @Test
    fun `unlisted durations are rejected even when shorter than forty eight hours`() {
        for (duration in listOf(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMinutes(14), Duration.ofHours(1), Duration.ofHours(49))) {
            assertFailsWith<IllegalArgumentException> { closesAt(openedAt, duration) }
        }
    }

    @Test
    fun `payment keys are stable bounded and distinct for each pledge and operation`() {
        val pledgeId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        assertEquals("5daf7186-9dba-3253-a7f8-4e66450fc614", idempotencyKey(pledgeId, PaymentAction.CAPTURE))
        val keys = PaymentAction.entries.map { action ->
            val key = idempotencyKey(pledgeId, action)
            assertEquals(36, key.length)
            assertEquals(key, idempotencyKey(pledgeId, action))
            assertEquals(key, UUID.fromString(key).toString())
            key
        }
        assertEquals(PaymentAction.entries.size, keys.toSet().size)
        val other = UUID.fromString("00000000-0000-0000-0000-000000000002")
        assertTrue(keys.toSet().intersect(PaymentAction.entries.map { idempotencyKey(other, it) }.toSet()).isEmpty())
    }

    private fun pledge(state: PledgeState, qty: Int) = Pledge(
        campaignId = UUID.randomUUID(), qty = qty, amount = 1_500L * qty,
        currency = "USD", state = state, createdAt = openedAt,
    )
}

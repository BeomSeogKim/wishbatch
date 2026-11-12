package app.campaign.trail

import app.campaign.model.Event
import app.campaign.model.Pledge
import app.web.trail.csvCell
import app.web.trail.trailCsv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class MoneyTrailTests {
    @Test
    fun `state checkpoints are retained and partial amount is unknown`() {
        val campaignId = UUID.randomUUID()
        val time = Instant.parse("2026-01-01T00:00:00Z")
        val pledge = Pledge(campaignId, 1, 650, "USD", createdAt = time, captureId = "capture")
        val events = listOf(
            Event(campaignId, "CAPTURE_RESPONSE", pledge.id, paypalStatus = "COMPLETED", createdAt = time),
            Event(campaignId, "PLEDGE_STATE", pledge.id, detail = "AUTHORIZED -> CAPTURE_PENDING", createdAt = time),
            Event(campaignId, "PLEDGE_STATE", pledge.id, detail = "CAPTURE_PENDING -> CAPTURED", createdAt = time.plusSeconds(1)),
            Event(campaignId, "PLEDGE_STATE", pledge.id, detail = "CAPTURE_PENDING -> CAPTURED", createdAt = time.plusSeconds(2)),
            Event(campaignId, "PLEDGE_STATE", pledge.id, detail = "CAPTURED -> PARTIALLY_REFUNDED", createdAt = time.plusSeconds(3)),
        )
        val result = trailEntries(listOf(pledge), events)
        assertEquals(listOf("Payment pending", "Payment confirmed", "Payment confirmed", "Partial refund observed"), result.map { it.status })
        assertEquals(time.plusSeconds(1), result[1].checkpoint)
        assertEquals("Unknown", result.last().amount)
    }

    @Test
    fun `repeated pending transitions remain ordered in timeline and csv`() {
        val campaignId = UUID.randomUUID()
        val time = Instant.parse("2026-01-01T00:00:00Z")
        val pledge = Pledge(campaignId, 1, 650, "USD", createdAt = time, refundId = "refund-1")
        val events = listOf(
            Event(campaignId, "REFUND_REQUEST", pledge.id, paypalId = "request-only", createdAt = time),
            Event(campaignId, "PLEDGE_STATE", pledge.id, detail = "CAPTURED -> REFUND_PENDING", createdAt = time.plusSeconds(1)),
            Event(campaignId, "PLEDGE_STATE", pledge.id, detail = "REFUND_PENDING -> REFUND_FAILED", createdAt = time.plusSeconds(2)),
            Event(campaignId, "REFUND_REQUEST", pledge.id, paypalId = "request-only", createdAt = time.plusSeconds(3)),
            Event(campaignId, "PLEDGE_STATE", pledge.id, detail = "REFUND_FAILED -> REFUND_PENDING", createdAt = time.plusSeconds(4)),
        )
        val entries = trailEntries(listOf(pledge), events)
        assertEquals(listOf("Refund pending", "Refund failed", "Refund pending"), entries.map { it.status })
        assertEquals(listOf(time.plusSeconds(1), time.plusSeconds(2), time.plusSeconds(4)), entries.map { it.checkpoint })
        assertEquals(listOf("refund-1", "refund-1", "refund-1"), entries.map { it.providerId })
        val lines = trailCsv(entries).trim().split("\r\n")
        assertEquals(4, lines.size)
        assertEquals(true, lines[1].contains("2026-01-01T00:00:01Z\",\"Refund pending"))
        assertEquals(true, lines[2].contains("2026-01-01T00:00:02Z\",\"Refund failed"))
        assertEquals(true, lines[3].contains("2026-01-01T00:00:04Z\",\"Refund pending"))
        assertEquals(false, lines.any { it.contains("request-only") })
    }

    @Test
    fun `closing an unapproved order does not claim a hold was released`() {
        val campaignId = UUID.randomUUID()
        val time = Instant.parse("2026-01-01T00:00:00Z")
        val pledge = Pledge(campaignId, 1, 650, "USD", createdAt = time, paypalOrderId = "order")
        val event = Event(campaignId, "PLEDGE_STATE", pledge.id, detail = "CREATED -> VOIDED", createdAt = time)
        assertEquals("Order closed", trailEntries(listOf(pledge), listOf(event)).single().status)
    }

    @Test
    fun `csv quotes and neutralizes spreadsheet formulas`() {
        assertEquals("\"' =1+1\"", csvCell(" =1+1"))
        assertEquals("\"'\ttext\"", csvCell("\ttext"))
        assertEquals("\"a,\"\"b\"\"\"", csvCell("a,\"b\""))
    }
}

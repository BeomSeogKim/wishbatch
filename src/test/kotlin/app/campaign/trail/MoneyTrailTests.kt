package app.campaign.trail

import app.campaign.model.Event
import app.campaign.model.Pledge
import app.web.trail.csvCell
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class MoneyTrailTests {
    @Test
    fun `only validated state checkpoints are shown once and partial amount is unknown`() {
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
        assertEquals(listOf("Payment pending", "Payment confirmed", "Partial refund observed"), result.map { it.status })
        assertEquals(time.plusSeconds(1), result[1].checkpoint)
        assertEquals("Unknown", result.last().amount)
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

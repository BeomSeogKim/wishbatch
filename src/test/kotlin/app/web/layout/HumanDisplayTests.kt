package app.web.layout

import app.campaign.model.CampaignState
import app.campaign.model.PledgeState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

class HumanDisplayTests {
    private val display = HumanDisplay()

    @Test
    fun `time uses UTC minute precision`() {
        assertEquals("12 Nov 2026, 14:05 UTC", display.time(Instant.parse("2026-11-12T23:05:59.123456+09:00")))
        assertEquals("Not available", display.time(null))
    }

    @Test
    fun `money preserves exact minor units with currency precision`() {
        assertEquals("USD 92233720368547758.07", display.money(Long.MAX_VALUE, "USD"))
        assertEquals("USD -92233720368547758.08", display.money(Long.MIN_VALUE, "USD"))
        assertEquals("USD 1234567890123456789012345678.90", display.money(BigInteger("123456789012345678901234567890"), "USD"))
        assertEquals("USD 0.00", display.money(0, "USD"))
        assertEquals("USD -0.01", display.money(-1, "USD"))
        assertEquals("JPY 123", display.money(123, "JPY"))
        assertEquals("KWD 0.123", display.money(123, "KWD"))
        assertEquals("USD 6.50", display.trailMoney("650 USD minor units"))
        assertEquals("Unknown", display.trailMoney("Unknown"))
    }

    @Test
    fun `labels distinguish holds payments pending and historical outcomes`() {
        assertEquals("Funded", display.state(CampaignState.FUNDED))
        assertEquals("Hold placed", display.state(PledgeState.AUTHORIZED))
        assertEquals("Paid", display.state(PledgeState.CAPTURED))
        assertEquals("Payment pending", display.state(PledgeState.CAPTURE_PENDING))
        assertEquals("Partially refunded", display.state(PledgeState.PARTIALLY_REFUNDED))
        assertEquals("Unknown", display.state(null))
        assertEquals("New provider state", display.state("NEW_PROVIDER_STATE"))
        assertEquals("Not funded / Completed payments below minimum", display.state("NOT_FUNDED / CAPTURE_SHORTFALL"))
    }

    @Test
    fun `short IDs leave the source untouched`() {
        val id = UUID.fromString("12345678-1234-5678-1234-567812345678")
        assertEquals("12345678…", display.shortId(id))
        assertEquals("12345678-1234-5678-1234-567812345678", id.toString())
        assertEquals("cap-1", display.shortId("cap-1"))
        assertEquals("Unknown", display.shortId(null))
    }
}

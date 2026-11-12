package app.web.provenance

import app.web.layout.HumanDisplay
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals

class TermsCheckDisplayTests {
    private val display = TermsCheckDisplay(JsonMapper.builder().build(), HumanDisplay())

    @Test
    fun `summarizes recorded outcomes and handles missing or invalid evidence`() {
        assertEquals("Terms matched PayPal · At authorization · Expected USD 48.00 · 3 units", display.fromJson(
            """{"outcome":"MATCH","stage":"AUTHORIZATION","expectedAmountMinor":4800,"expectedCurrency":"USD","quantity":3}"""))
        assertEquals("Terms did not match · 1 unit", display.fromJson("""{"outcome":"MISMATCH","quantity":1}"""))
        assertEquals("Terms check unavailable", display.fromJson("""{"outcome":"UNAVAILABLE"}"""))
        assertEquals("Terms matched PayPal · Before capture", display.fromJson("""{"outcome":"MATCH","stage":"PRE_CAPTURE"}"""))
        assertEquals("Terms check unavailable", display.fromJson("invalid"))
        assertEquals("Terms check unavailable", display.fromJson(null))
    }
}

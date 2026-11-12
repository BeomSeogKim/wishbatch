package app.campaign

import app.campaign.rules.MoneyInput
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class MoneyInputTests {
    @Test
    fun `parses whole and fractional amounts including zero and leading zeros`() {
        val cases = mapOf(
            "6" to 600L,
            "6.5" to 650L,
            "6.50" to 650L,
            "0" to 0L,
            "0.0" to 0L,
            "0.00" to 0L,
            "0.01" to 1L,
            "0.1" to 10L,
            "0006.05" to 605L,
            "0000" to 0L,
            "\t 6.50\r\n" to 650L,
            "90071992547409.93" to 9007199254740993L,
        )
        for ((raw, expected) in cases) {
            assertEquals(expected, MoneyInput.parseMinorUnits(raw), raw)
        }
    }

    @Test
    fun `rejects signs separators incomplete decimals and non ASCII digits`() {
        val invalid = listOf(
            "", " \t\n", "-6", "-0", "+6", "1,000", "6,50", "6_50", "6/50",
            "6e2", "6E2", ".5", "6.", "6.500", "0.001", "6.5.0", "6 50",
            "6 .50", "6. 50", "6\n.50", "６.５０", "٦.٥٠", "6.٥", "6.50x",
        )
        for (raw in invalid) {
            assertNull(MoneyInput.parseMinorUnits(raw), raw)
        }
    }

    @Test
    fun `accepts the exact Long boundary and rejects any overflow`() {
        assertEquals(Long.MAX_VALUE, MoneyInput.parseMinorUnits("92233720368547758.07"))
        assertEquals(Long.MAX_VALUE, MoneyInput.parseMinorUnits("00092233720368547758.07"))
        assertEquals(Long.MAX_VALUE - 7, MoneyInput.parseMinorUnits("92233720368547758"))
        for (raw in listOf(
            "92233720368547758.08", "92233720368547758.1", "92233720368547759",
            "9223372036854775807", "999999999999999999999999999999.99",
        )) {
            assertNull(MoneyInput.parseMinorUnits(raw), raw)
        }
    }

    @Test
    fun `formats exactly two decimals and roundtrips without losing precision`() {
        val cases = mapOf(
            0L to "0.00",
            1L to "0.01",
            10L to "0.10",
            99L to "0.99",
            100L to "1.00",
            605L to "6.05",
            650L to "6.50",
            9007199254740993L to "90071992547409.93",
            Long.MAX_VALUE - 1 to "92233720368547758.06",
            Long.MAX_VALUE to "92233720368547758.07",
        )
        for ((amount, expected) in cases) {
            assertEquals(expected, MoneyInput.formatMinorUnits(amount))
            assertEquals(amount, MoneyInput.parseMinorUnits(MoneyInput.formatMinorUnits(amount)))
        }
    }

    @Test
    fun `formatting rejects negative amounts clearly`() {
        for (amount in listOf(-1L, Long.MIN_VALUE)) {
            val error = assertFailsWith<IllegalArgumentException> { MoneyInput.formatMinorUnits(amount) }
            assertEquals("Amount must be nonnegative", error.message)
        }
    }
}

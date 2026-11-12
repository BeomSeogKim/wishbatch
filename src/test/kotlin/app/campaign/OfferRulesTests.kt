package app.campaign

import app.campaign.model.Offer
import app.campaign.model.Option
import app.campaign.rules.CapacityDecision
import app.campaign.rules.OfferViolation
import app.campaign.rules.OfferWarning
import app.campaign.rules.checkCapacity
import app.campaign.rules.validateOffer
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfferRulesTests {
    private val option = Option(
        workspaceId = UUID.randomUUID(), name = "Print", format = "A5", fixedAttributes = emptyMap(),
        allowedVariants = mapOf("finish" to listOf("matte", "glossy")), unitCost = 650,
        currency = "USD", minBatch = 3, maxCapacity = 10, leadTimeText = "Two weeks", shipRegion = "US",
    )

    @Test
    fun `offer accepts exact minimum capacity and quantity limits`() {
        for (quantity in listOf(1, 3, 10)) {
            val result = validateOffer(offer(maxQty = quantity), option, Duration.ofHours(48))
            assertTrue(result.valid)
            assertTrue(result.warnings.isEmpty())
        }
        assertEquals(3, offer().maxQtyPerPledge)
        assertTrue(validateOffer(offer(minBatch = 10), option, Duration.ofMinutes(15)).valid)
    }

    @Test
    fun `offer reports all invalid commercial limits`() {
        val cases = listOf(
            offer(minBatch = 2) to OfferViolation.MINIMUM_BELOW_OPTION,
            offer(minBatch = 0) to OfferViolation.MINIMUM_BELOW_OPTION,
            offer(minBatch = 11) to OfferViolation.MINIMUM_EXCEEDS_CAPACITY,
            offer(capacity = 0) to OfferViolation.NON_POSITIVE_CAPACITY,
            offer(capacity = 11) to OfferViolation.CAPACITY_ABOVE_OPTION,
            offer(price = 0) to OfferViolation.NON_POSITIVE_PRICE,
            offer(price = -1) to OfferViolation.NON_POSITIVE_PRICE,
            offer(maxQty = 0) to OfferViolation.QUANTITY_OUT_OF_RANGE,
            offer(maxQty = 11) to OfferViolation.QUANTITY_OUT_OF_RANGE,
            offer(currency = "EUR") to OfferViolation.CURRENCY_MISMATCH,
        )
        for ((offer, violation) in cases) {
            val result = validateOffer(offer, option, Duration.ofHours(24))
            assertFalse(result.valid, violation.name)
            assertTrue(violation in result.violations, violation.name)
        }
        for (duration in listOf(Duration.ZERO, Duration.ofHours(1), Duration.ofHours(49))) {
            assertEquals(setOf(OfferViolation.INVALID_DURATION), validateOffer(offer(), option, duration).violations)
        }
        val combined = validateOffer(offer(minBatch = 1, capacity = 20, price = 0, maxQty = 0), option, Duration.ofHours(49))
        assertEquals(5, combined.violations.size)
    }

    @Test
    fun `below cost warns without blocking and never compares different currencies`() {
        val below = validateOffer(offer(price = 649), option, Duration.ofHours(2))
        assertTrue(below.valid)
        assertEquals(setOf(OfferWarning.PRICE_BELOW_COST), below.warnings)
        assertTrue(validateOffer(offer(price = 650), option, Duration.ofHours(2)).warnings.isEmpty())
        assertTrue(validateOffer(offer(price = 1, currency = "EUR"), option, Duration.ofHours(2)).warnings.isEmpty())
    }

    @Test
    fun `offer cannot borrow limits from another option or workspace`() {
        assertFailsWith<IllegalArgumentException> {
            validateOffer(offer(optionId = UUID.randomUUID()), option, Duration.ofHours(24))
        }
        assertFailsWith<IllegalArgumentException> {
            validateOffer(offer(workspaceId = UUID.randomUUID()), option, Duration.ofHours(24))
        }
    }

    @Test
    fun `capacity admits exact fit and rejects invalid quantities and overflow`() {
        assertEquals(CapacityDecision.ALLOWED, checkCapacity(10, 0, 3, 3))
        assertEquals(CapacityDecision.ALLOWED, checkCapacity(10, 7, 3, 3))
        assertEquals(CapacityDecision.CAPACITY_EXCEEDED, checkCapacity(10, 8, 3, 3))
        assertEquals(CapacityDecision.CAPACITY_EXCEEDED, checkCapacity(10, 10, 1, 3))
        assertEquals(CapacityDecision.CAPACITY_EXCEEDED, checkCapacity(10, Long.MAX_VALUE, 1, 3))
        for (qty in listOf(-1, 0, 4, Int.MAX_VALUE)) {
            assertEquals(CapacityDecision.INVALID_QUANTITY, checkCapacity(10, 0, qty, 3))
        }
        assertEquals(CapacityDecision.ALLOWED, checkCapacity(Int.MAX_VALUE, 0, Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun `capacity must be checked again after competing authorization`() {
        assertEquals(CapacityDecision.ALLOWED, checkCapacity(10, 7, 3, 3))
        assertEquals(CapacityDecision.CAPACITY_EXCEEDED, checkCapacity(10, 8, 3, 3))
        assertFailsWith<IllegalArgumentException> { checkCapacity(0, 0, 1, 1) }
        assertFailsWith<IllegalArgumentException> { checkCapacity(10, -1, 1, 3) }
        assertFailsWith<IllegalArgumentException> { checkCapacity(10, 0, 1, 0) }
        assertFailsWith<IllegalArgumentException> { checkCapacity(10, 0, 1, 11) }
    }

    private fun offer(
        minBatch: Int = 3, capacity: Int = 10, price: Long = 1_500, maxQty: Int = 3, currency: String = "USD",
        optionId: UUID = option.id, workspaceId: UUID = option.workspaceId,
    ) = Offer(
        workspaceId = workspaceId, optionId = optionId, analysisRunId = UUID.randomUUID(), title = "Print batch",
        specText = "One A5 print", chosenVariants = mapOf("finish" to "matte"), unitPrice = price, currency = currency,
        deliveryTerms = "Ships in two weeks", shipRegion = "US", minBatch = minBatch, capacity = capacity, maxQtyPerPledge = maxQty,
    )
}

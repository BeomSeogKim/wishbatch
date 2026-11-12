package app.campaign

import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Money
import app.campaign.model.NeedAttribute
import app.campaign.model.NeedStrength
import app.campaign.model.Option
import app.campaign.model.WishKind
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalyzedWish
import app.campaign.port.WishNeed
import app.campaign.port.WishVerdict
import app.campaign.rules.offerPriceCheck
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class OfferPriceRulesTests {
    private val option = Option(UUID(0, 1), "Cards", "cards", emptyMap(), emptyMap(),
        650, "USD", 3, 10, "Two weeks", "US", UUID(0, 2))

    @Test
    fun `raising price past a stated limit excludes compatible and conditional wishes`() {
        val compatible = wish(3, 1000)
        val conditional = wish(4, 1200)
        val output = output(compatible to CompatibilityVerdict.COMPATIBLE, conditional to CompatibilityVerdict.CONDITIONAL)

        val equal = offerPriceCheck(output, option, 1000)
        assertEquals(1, equal.compatible)
        assertEquals(1, equal.depends)
        assertEquals(2, equal.fit)
        assertEquals(emptyList(), equal.excludedByPrice)

        val above = offerPriceCheck(output, option, 1001)
        assertEquals(0, above.compatible)
        assertEquals(1, above.depends)
        assertEquals(1, above.fit)
        assertEquals(listOf(compatible), above.excludedByPrice)
        assertEquals("At most USD 10.00", above.excludedByPrice.single().needs.single().quote)

        val highest = offerPriceCheck(output, option, 1201)
        assertEquals(0, highest.fit)
        assertEquals(listOf(compatible, conditional), highest.excludedByPrice)
        assertEquals(equal, offerPriceCheck(output, option, 1000))
        assertEquals(listOf(compatible, conditional), output.wishes)
    }

    @Test
    fun `unstated limits and different currencies do not exclude wishes`() {
        val unlimited = wish(3, null)
        val otherCurrency = wish(4, 1, "EUR")
        val output = output(unlimited to CompatibilityVerdict.COMPATIBLE, otherCurrency to CompatibilityVerdict.CONDITIONAL)
        val result = offerPriceCheck(output, option, 9999)
        assertEquals(1, result.compatible)
        assertEquals(1, result.depends)
        assertEquals(2, result.fit)
        assertEquals(emptyList(), result.excludedByPrice)
    }

    @Test
    fun `incompatible wishes and non requests never fit or become excluded by price`() {
        val incompatible = wish(3, 1)
        val nonRequest = wish(4, 1).copy(kind = WishKind.NOT_A_REQUEST)
        val base = output(incompatible to CompatibilityVerdict.INCOMPATIBLE)
        val result = offerPriceCheck(base.copy(wishes = base.wishes + nonRequest), option, 1000)
        assertEquals(0, result.compatible)
        assertEquals(0, result.depends)
        assertEquals(0, result.fit)
        assertEquals(emptyList(), result.excludedByPrice)
    }

    @Test
    fun `only the selected option verdict contributes to counts and exclusions`() {
        val request = wish(3, 100)
        val base = output(request to CompatibilityVerdict.INCOMPATIBLE)
        val other = WishVerdict(request.wishId, UUID(0, 99), CompatibilityVerdict.COMPATIBLE, "Other format fits")
        val result = offerPriceCheck(base.copy(verdicts = base.verdicts + other, optionIds = base.optionIds + other.optionId), option, 101)
        assertEquals(0, result.fit)
        assertEquals(emptyList(), result.excludedByPrice)
    }

    @Test
    fun `comparison preserves adjacent large minor unit values without floating point`() {
        val lower = wish(3, Long.MAX_VALUE - 1)
        val equal = wish(4, Long.MAX_VALUE)
        val result = offerPriceCheck(output(lower to CompatibilityVerdict.COMPATIBLE, equal to CompatibilityVerdict.CONDITIONAL),
            option, Long.MAX_VALUE)
        assertEquals(0, result.compatible)
        assertEquals(1, result.depends)
        assertEquals(listOf(lower), result.excludedByPrice)
    }

    private fun wish(id: Long, limit: Long?, currency: String = "USD") = AnalyzedWish(
        UUID(0, id), WishKind.REQUEST,
        if (limit == null) emptyList() else listOf(WishNeed(NeedStrength.HARD, NeedAttribute.PRICE,
            "Stated price limit", "At most USD 10.00")),
        limit?.let { Money(it, currency) },
    )

    private fun output(vararg requests: Pair<AnalyzedWish, CompatibilityVerdict>) = AnalysisOutput(
        wishes = requests.map { it.first },
        verdicts = requests.map { (wish, verdict) -> WishVerdict(wish.wishId, option.id, verdict, "Format assessment") },
        proposal = null,
        noProposalReason = "Not needed for a price check",
        notServed = emptyList(),
        questionsForMaker = emptyList(),
        alternatives = emptyList(),
        optionIds = listOf(option.id),
    )
}

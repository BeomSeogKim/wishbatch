package app.campaign

import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Money
import app.campaign.model.NeedAttribute
import app.campaign.model.NeedStrength
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.model.WishSource
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalyzedWish
import app.campaign.port.WishNeed
import app.campaign.port.WishVerdict
import app.campaign.review.WishReviewOverride
import app.campaign.review.reviewedAnalysis
import app.campaign.rules.offerInterest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals

class OfferPriceRulesTests {
    private val option = Option(UUID(0, 1), "Cards", "cards", emptyMap(), emptyMap(),
        650, "USD", 3, 10, "Two weeks", "US", UUID(0, 2))

    @Test
    fun `visible request labels produce disjoint offer groups without quantities or duplicate imports`() {
        val compatible = wish(3)
        val conditional = wish(4).copy(needs = listOf(WishNeed(NeedStrength.UNKNOWN, NeedAttribute.FINISH, "Blue", "Blue")))
        val incompatible = wish(5, 1)
        val nonRequest = wish(6).copy(kind = WishKind.NOT_A_REQUEST)
        val hidden = wish(7)
        val foreign = wish(8)
        val output = output(compatible to CompatibilityVerdict.COMPATIBLE, conditional to CompatibilityVerdict.CONDITIONAL,
            incompatible to CompatibilityVerdict.INCOMPATIBLE, nonRequest to CompatibilityVerdict.COMPATIBLE,
            hidden to CompatibilityVerdict.COMPATIBLE, foreign to CompatibilityVerdict.COMPATIBLE)
        val sources = output.wishes.map(::source).toMutableList()
        sources[0].duplicateCount = 99
        sources[4].hidden = true
        sources[5] = Wish(UUID(0, 99), "Cards", WishSource.ONSITE, createdAt = Instant.EPOCH, id = foreign.wishId)
        val result = offerInterest(output.copy(wishes = output.wishes + compatible,
            verdicts = output.verdicts + output.verdicts.first()), option, sources, 1000)
        assertEquals(listOf(compatible.wishId), result.interestWishIds)
        assertEquals(listOf(conditional.wishId), result.conditionalWishIds)
        assertEquals(listOf(incompatible.wishId), result.notServedWishIds)
        assertEquals(emptyList(), result.excludedByPriceWishIds)
        assertEquals(listOf(1, 1, 0, 1), listOf(result.interest, result.conditional, result.excludedByPrice, result.notServed))
    }

    @Test
    fun `price limits above and equal fit while below excludes compatible and conditional wishes`() {
        val compatible = wish(3, 1000)
        val conditional = wish(4, 1200).let { it.copy(needs = it.needs +
            WishNeed(NeedStrength.UNKNOWN, NeedAttribute.FINISH, "Blue", "Blue")) }
        val output = output(compatible to CompatibilityVerdict.COMPATIBLE, conditional to CompatibilityVerdict.CONDITIONAL)
        val sources = output.wishes.map(::source)
        for (price in listOf(999L, 1000L)) {
            val result = offerInterest(output, option, sources, price)
            assertEquals(1, result.interest)
            assertEquals(1, result.conditional)
            assertEquals(0, result.excludedByPrice)
        }
        val above = offerInterest(output, option, sources, 1001)
        assertEquals(listOf(compatible.wishId), above.excludedByPriceWishIds)
        assertEquals(listOf(conditional.wishId), above.conditionalWishIds)
        val highest = offerInterest(output, option, sources, 1201)
        assertEquals(0, highest.interest)
        assertEquals(0, highest.conditional)
        assertEquals(listOf(compatible.wishId, conditional.wishId), highest.excludedByPriceWishIds)
    }

    @Test
    fun `price-only conditions resolve at the chosen price but mixed or unquoted conditions do not`() {
        val priceOnly = wish(3, 1000)
        val unknownPrice = wish(4, 1000).let { it.copy(needs = it.needs.map { need -> need.copy(strength = NeedStrength.UNKNOWN) }) }
        val unquoted = wish(5, 1000)
        val output = output(priceOnly to CompatibilityVerdict.CONDITIONAL, unknownPrice to CompatibilityVerdict.CONDITIONAL,
            unquoted to CompatibilityVerdict.CONDITIONAL)
        val sources = output.wishes.map(::source).dropLast(1) +
            Wish(option.workspaceId, "Cards please", WishSource.ONSITE, createdAt = Instant.EPOCH, id = unquoted.wishId)
        val result = offerInterest(output, option, sources, 1000)
        assertEquals(listOf(priceOnly.wishId, unknownPrice.wishId), result.interestWishIds)
        assertEquals(listOf(unquoted.wishId), result.conditionalWishIds)
        assertEquals(2, offerInterest(output, option, sources, 1001).excludedByPrice)
    }

    @Test
    fun `unstated ungrounded and foreign-currency limits never exclude a compatible request`() {
        val unlimited = wish(3)
        val otherCurrency = wish(4, 1, "EUR")
        val ungrounded = wish(5, 1)
        val output = output(unlimited to CompatibilityVerdict.COMPATIBLE, otherCurrency to CompatibilityVerdict.COMPATIBLE,
            ungrounded to CompatibilityVerdict.COMPATIBLE)
        val sources = output.wishes.map(::source).dropLast(1) +
            Wish(option.workspaceId, "Cards please", WishSource.ONSITE, createdAt = Instant.EPOCH, id = ungrounded.wishId)
        val result = offerInterest(output, option, sources, 9999)
        assertEquals(3, result.interest)
        assertEquals(0, result.excludedByPrice)
    }

    @Test
    fun `literal price evidence overrides disagreeing normalized model amounts`() {
        val request = wish(3, 1000).copy(maxUnitPrice = Money(1, "USD"))
        val output = output(request to CompatibilityVerdict.CONDITIONAL)
        val sources = listOf(source(request))
        assertEquals(1, offerInterest(output, option, sources, 1000).interest)
        assertEquals(1, offerInterest(output, option, sources, 1001).excludedByPrice)
        val withoutNormalized = output.copy(wishes = listOf(request.copy(maxUnitPrice = null)))
        assertEquals(1, offerInterest(withoutNormalized, option, sources, 1000).interest)
    }

    @Test
    fun `ordinary card requests retain exact caps alongside unresolved design conditions`() {
        val compatible = wish(3, 1000).copy(maxUnitPrice = Money(1, "USD"))
        val conditional = wish(4, 1200).let { it.copy(needs = it.needs +
            WishNeed(NeedStrength.UNKNOWN, NeedAttribute.OTHER, "Designs must fit", "if the designs fit")) }
        val output = output(compatible to CompatibilityVerdict.COMPATIBLE, conditional to CompatibilityVerdict.CONDITIONAL)
        val sources = listOf(
            Wish(option.workspaceId, "Matte cards please. At most USD 10.00", WishSource.ONSITE,
                createdAt = Instant.EPOCH, id = compatible.wishId),
            Wish(option.workspaceId, "Postcards if the designs fit. At most USD 12.00", WishSource.ONSITE,
                createdAt = Instant.EPOCH, id = conditional.wishId),
        )
        val atCap = offerInterest(output, option, sources, 1000)
        assertEquals(listOf(compatible.wishId), atCap.interestWishIds)
        assertEquals(listOf(conditional.wishId), atCap.conditionalWishIds)
        val aboveFirst = offerInterest(output, option, sources, 1001)
        assertEquals(listOf(compatible.wishId), aboveFirst.excludedByPriceWishIds)
        assertEquals(listOf(conditional.wishId), aboveFirst.conditionalWishIds)
        val aboveBoth = offerInterest(output, option, sources, 1201)
        assertEquals(listOf(compatible.wishId, conditional.wishId), aboveBoth.excludedByPriceWishIds)
        assertEquals(0, aboveBoth.conditional)
    }

    @Test
    fun `unsupported aggregate shipping and foreign price quotes remain unknown`() {
        for (quote in listOf("Up to EUR 10", "Up to USD 10 including shipping", "Up to USD 10 for two", "Roughly USD 10")) {
            val request = wish(3, 1).let { it.copy(needs = it.needs.map { need -> need.copy(quote = quote) }) }
            val output = output(request to CompatibilityVerdict.CONDITIONAL)
            val result = offerInterest(output, option, listOf(source(request)), 1000)
            assertEquals(1, result.conditional, quote)
            assertEquals(0, result.excludedByPrice, quote)
            assertEquals(0, result.interest, quote)
        }
    }

    @Test
    fun `source context cannot turn aggregate foreign historical or negated amounts into unit caps`() {
        for (text in listOf("CAD up to $10", "up to $10 for two", "up to $10 including shipping",
            "I used to pay up to $10", "I will not pay up to $10", "up to $10. This is the combined total.",
            "An unsupported context up to $10")) {
            val request = wish(3, 1000).let { it.copy(needs = it.needs.map { need -> need.copy(quote = "up to $10") }) }
            val source = Wish(option.workspaceId, text, WishSource.ONSITE, createdAt = Instant.EPOCH, id = request.wishId)
            val result = offerInterest(output(request to CompatibilityVerdict.CONDITIONAL), option, listOf(source), 1200)
            assertEquals(1, result.conditional, text)
            assertEquals(0, result.excludedByPrice, text)
            assertEquals(0, result.interest, text)
        }
    }

    @Test
    fun `sample postcard price conditions resolve once exact catalog format requirements are satisfied`() {
        val cards = Option(option.workspaceId, "A6 postcard set, 5 designs", "postcard-set",
            mapOf("size" to "A6", "designCount" to "5"), mapOf("finish" to listOf("matte", "glossy")),
            650, "USD", 3, 10, "Two weeks", "US", option.id)
        val examples = listOf(
            listOf("I'd get the five-card set if the whole set costs less than $15. That's my firm treat budget this month.",
                "five-card set", "the five-card set", "if the whole set costs less than $15"),
            listOf("I'm in for a postcard set only at $10 or less per set. Trying to leave myself some grocery money!",
                "postcard-set", "a postcard set", "only at $10 or less per set"),
        )
        for ((index, example) in examples.withIndex()) {
            val request = wish(3, 1).copy(needs = listOf(
                WishNeed(NeedStrength.HARD, NeedAttribute.FORMAT, example[1], example[2]),
                WishNeed(NeedStrength.HARD, NeedAttribute.PRICE, "Quoted cap", example[3]),
            ))
            val source = Wish(option.workspaceId, example[0], WishSource.ONSITE, createdAt = Instant.EPOCH, id = request.wishId)
            val output = output(request to CompatibilityVerdict.CONDITIONAL)
            val cap = if (index == 0) 1499L else 1000L
            assertEquals(1, offerInterest(output, cards, listOf(source), cap).interest)
            assertEquals(1, offerInterest(output, cards, listOf(source), cap + 1).excludedByPrice)
            val unknown = request.copy(needs = request.needs.map { if (it.attribute == NeedAttribute.FORMAT)
                it.copy(strength = NeedStrength.UNKNOWN) else it })
            assertEquals(1, offerInterest(output.copy(wishes = listOf(unknown)), cards, listOf(source), cap).conditional)
        }
    }

    @Test
    fun `maker overrides and resolved fan answers are counted from their current verdicts`() {
        val request = wish(3)
        val base = output(request to CompatibilityVerdict.CONDITIONAL)
        fun decision(verdict: CompatibilityVerdict, revision: Long, source: String = "MAKER") = WishReviewOverride(
            option.workspaceId, UUID(0, 10), request.wishId, option.id, verdict, CompatibilityVerdict.CONDITIONAL,
            "Reviewed condition", Instant.EPOCH, revision, source = source,
            answerId = if (source == "FAN_ANSWER") UUID(0, 11) else null)
        val sources = listOf(source(request))
        val answered = reviewedAnalysis(base, listOf(decision(CompatibilityVerdict.COMPATIBLE, 1, "FAN_ANSWER")))
        assertEquals(1, offerInterest(answered, option, sources, 1000).interest)
        val overridden = reviewedAnalysis(base, listOf(decision(CompatibilityVerdict.COMPATIBLE, 1),
            decision(CompatibilityVerdict.INCOMPATIBLE, 2)))
        assertEquals(1, offerInterest(overridden, option, sources, 1000).notServed)
        assertEquals(0, offerInterest(overridden, option, sources, 1000).interest)
    }

    @Test
    fun `only selected option counts and adjacent large prices remain exact`() {
        val lower = wish(3, Long.MAX_VALUE - 1)
        val equal = wish(4, Long.MAX_VALUE)
        val base = output(lower to CompatibilityVerdict.COMPATIBLE, equal to CompatibilityVerdict.CONDITIONAL)
        val other = WishVerdict(lower.wishId, UUID(0, 99), CompatibilityVerdict.INCOMPATIBLE, "Other format")
        val result = offerInterest(base.copy(verdicts = base.verdicts + other), option, base.wishes.map(::source), Long.MAX_VALUE)
        assertEquals(listOf(lower.wishId), result.excludedByPriceWishIds)
        assertEquals(listOf(equal.wishId), result.interestWishIds)
    }

    private fun wish(id: Long, limit: Long? = null, currency: String = "USD") = AnalyzedWish(
        UUID(0, id), WishKind.REQUEST,
        if (limit == null) emptyList() else listOf(WishNeed(NeedStrength.HARD, NeedAttribute.PRICE,
            "Stated price limit", "At most $currency ${BigDecimal.valueOf(limit, 2).toPlainString()}")),
        limit?.let { Money(it, currency) }, quantity = 100,
    )

    private fun source(wish: AnalyzedWish) = Wish(option.workspaceId,
        wish.needs.joinToString("; ") { it.quote }.ifBlank { "Cards please" },
        WishSource.ONSITE, createdAt = Instant.EPOCH, id = wish.wishId)

    private fun output(vararg requests: Pair<AnalyzedWish, CompatibilityVerdict>) = AnalysisOutput(
        wishes = requests.map { it.first },
        verdicts = requests.map { (wish, verdict) -> WishVerdict(wish.wishId, option.id, verdict, "Format assessment") },
        proposal = null, noProposalReason = "Not needed for a price check", notServed = emptyList(),
        questionsForMaker = emptyList(), alternatives = emptyList(), optionIds = listOf(option.id),
    )
}

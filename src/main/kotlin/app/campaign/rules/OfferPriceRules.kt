package app.campaign.rules

import app.campaign.demand.PriceEvidenceStatus
import app.campaign.demand.quotedPriceLimit
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.NeedAttribute
import app.campaign.model.NeedStrength
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalyzedWish
import app.campaign.port.WishNeed
import java.util.UUID

data class OfferInterest(
    val interestWishIds: List<UUID> = emptyList(),
    val conditionalWishIds: List<UUID> = emptyList(),
    val excludedByPriceWishIds: List<UUID> = emptyList(),
    val notServedWishIds: List<UUID> = emptyList(),
) {
    val interest: Int get() = interestWishIds.size
    val conditional: Int get() = conditionalWishIds.size
    val excludedByPrice: Int get() = excludedByPriceWishIds.size
    val notServed: Int get() = notServedWishIds.size
}

/** Uses effective reviewed verdicts; quantities and repeated imports never add interest. */
fun offerInterest(output: AnalysisOutput, option: Option, wishes: List<Wish>, unitPrice: Long): OfferInterest {
    require(unitPrice > 0)
    val visible = wishes.filter { !it.hidden && it.workspaceId == option.workspaceId }.associateBy { it.id }
    val requests = output.wishes.filter { it.kind == WishKind.REQUEST && it.wishId in visible }.associateBy { it.wishId }
    val verdicts = output.verdicts.filter { it.optionId == option.id }.associateBy { it.wishId }
    val interest = mutableListOf<UUID>()
    val conditional = mutableListOf<UUID>()
    val excluded = mutableListOf<UUID>()
    val notServed = mutableListOf<UUID>()
    for ((id, request) in requests.toSortedMap(compareBy(UUID::toString))) {
        val verdict = verdicts[id]?.verdict ?: continue
        val limit = offerPriceLimit(request, visible.getValue(id).text, option.currency)
        val conditions = request.needs.filter { it.strength != NeedStrength.PREFERENCE }
        val priceOnly = limit != null && conditions.any { it.attribute == NeedAttribute.PRICE } &&
            conditions.all { it.attribute == NeedAttribute.PRICE || catalogSatisfies(it, option, visible.getValue(id).text) }
        when {
            verdict == CompatibilityVerdict.INCOMPATIBLE -> notServed.add(id)
            limit != null && limit < unitPrice -> excluded.add(id)
            verdict == CompatibilityVerdict.COMPATIBLE || priceOnly -> interest.add(id)
            else -> conditional.add(id)
        }
    }
    return OfferInterest(interest, conditional, excluded, notServed)
}

/** Parse exact price evidence; model-normalized amounts never determine a price boundary. */
fun offerPriceLimit(wish: AnalyzedWish, source: String, currency: String): Long? {
    val prices = wish.needs.filter { it.attribute == NeedAttribute.PRICE }
    if (prices.isEmpty() || prices.any { it.quote.isBlank() || !source.contains(it.quote) }) return null
    if (!supportedPriceSource(wish, source)) return null
    val limits = prices.map { price ->
        val quoted = wish.copy(needs = listOf(price.copy(strength = NeedStrength.HARD)), maxUnitPrice = null)
        val parsed = quotedPriceLimit(quoted, price.quote, currency)
        if (parsed.status != PriceEvidenceStatus.QUOTED_LIMIT) return null
        parsed.maximumMinor ?: return null
    }
    return limits.minOrNull()
}

private fun supportedPriceSource(wish: AnalyzedWish, source: String): Boolean {
    val text = source.replace('\u2019', '\'').lowercase()
    if (Regex("\\b(?:cad|eur|gbp|aud|jpy|cny|krw|canadian|australian|euros?|yen|shipping|delivery|postage|combined|total|historical|previously|not|never)\\b|\\b(?:for (?:two|both|[2-9])|used to|last year)\\b|n't")
            .containsMatchIn(text)) return false
    var remainder = text
    wish.needs.map { it.quote }.filter { it.isNotBlank() && source.contains(it) }
        .distinct().sortedByDescending { it.length }.forEach { quote ->
            remainder = remainder.replace(quote.replace('\u2019', '\'').lowercase(), " ")
        }
    val request = Regex("(?:i|we)(?:'d| would| will)? (?:get|buy|order|like|want|take|can pay|can spend)|i(?:'m| am) in(?: for)?|(?:(?:matte|glossy) )?(?:cards|postcards|a postcard set)(?: please)?")
    val budget = Regex("that's my (?:firm )?(?:treat )?budget(?: this month)?|trying to leave myself some grocery money")
    return remainder.split(Regex("[.!?;]+"))
        .map { it.trim().trim(',').replace(Regex("\\s+"), " ") }
        .all { it.isEmpty() || request.matches(it) || budget.matches(it) }
}

private fun catalogSatisfies(need: WishNeed, option: Option, source: String): Boolean {
    if (need.strength != NeedStrength.HARD || need.quote.isBlank() || !source.contains(need.quote)) return false
    fun normalized(value: String) = value.lowercase().replace(Regex("[-_\\s]+"), " ").trim()
    val value = normalized(need.value)
    if (need.attribute == NeedAttribute.FORMAT) {
        if (value == normalized(option.format) || value == normalized(option.name)) return true
        val set = Regex("(one|two|three|four|five|six|seven|eight|nine|ten|[0-9]+) card set").matchEntire(value) ?: return false
        val number = set.groupValues[1].toIntOrNull() ?: listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
            .indexOf(set.groupValues[1]).plus(1)
        return normalized(option.format) in setOf("postcard set", "card set") && option.fixedAttributes["designCount"]?.toIntOrNull() == number
    }
    if (need.attribute !in setOf(NeedAttribute.SIZE, NeedAttribute.FINISH)) return false
    val key = need.attribute.name.lowercase()
    val fixed = option.fixedAttributes.entries.singleOrNull { normalized(it.key) == key }?.value
    val singleVariant = option.allowedVariants.entries.singleOrNull { normalized(it.key) == key }?.value?.singleOrNull()
    return listOfNotNull(fixed, singleVariant).any { normalized(it) == value }
}

package app.campaign.demand

import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.port.AnalysisOutput
import app.campaign.rules.offerInterest
import app.campaign.rules.offerPriceLimit
import java.util.UUID

data class DemandWish(val wishId: UUID, val text: String, val price: QuotedPriceLimit)
data class DemandPoint(val priceMinor: Long, val compatible: Int, val minimum: Int, val wishIds: List<UUID>)
data class DemandDifference(val otherName: String, val wishes: List<DemandWish>)
data class DemandAlternative(
    val optionId: UUID,
    val optionName: String,
    val compatible: Int,
    val wishIds: List<UUID>,
    val differences: List<DemandDifference>,
)
data class DemandExplorer(
    val optionName: String,
    val currency: String,
    val minimum: Int,
    val compatible: Int,
    val missing: Int,
    val ambiguous: Int,
    val unbounded: Int,
    val points: List<DemandPoint>,
    val wishes: List<DemandWish>,
    val alternatives: List<DemandAlternative>,
    val noSeriesReason: String? = null,
)

/** Takes the effective reviewed output; hidden wishes and repeated imports never inflate interest. */
fun exploreDemand(
    output: AnalysisOutput,
    options: List<Option>,
    wishes: List<Wish>,
    optionId: UUID,
    unitPrice: Long? = null,
    minimum: Int? = null,
): DemandExplorer? {
    val selected = options.singleOrNull { it.id == optionId && it.id in output.optionIds } ?: return null
    val sources = wishes.filter { !it.hidden && it.workspaceId == selected.workspaceId }.associateBy { it.id }
    val requests = output.wishes.filter { it.kind == WishKind.REQUEST && it.wishId in sources }.associateBy { it.wishId }
    val candidates = (listOf(selected) + options.filter {
        it.id != selected.id && it.workspaceId == selected.workspaceId && it.id in output.optionIds
    }.sortedBy { it.id.toString() })
    val candidatePrice = unitPrice?.takeIf { it > 0 } ?: selected.unitCost.coerceAtLeast(1)
    fun evidence(option: Option, ids: List<UUID>): List<DemandWish> = ids.map { id ->
        val request = requests.getValue(id)
        val source = sources.getValue(id).text
        val diagnostic = quotedPriceLimit(request, source, option.currency)
        val limit = offerPriceLimit(request, source, option.currency)
        val price = if (limit == null) diagnostic else
            QuotedPriceLimit(PriceEvidenceStatus.QUOTED_LIMIT, limit, diagnostic.quotes)
        DemandWish(id, source, price)
    }
    val counts = candidates.associate { it.id to offerInterest(output, it, wishes, candidatePrice) }
    val byOption = candidates.associate { it.id to evidence(it, counts.getValue(it.id).interestWishIds) }
    val selectedCounts = counts.getValue(selected.id)
    val chosen = evidence(selected, (selectedCounts.interestWishIds + selectedCounts.conditionalWishIds +
        selectedCounts.excludedByPriceWishIds).sortedBy(UUID::toString))
    val minimumUnits = minimum?.takeIf { it >= selected.minBatch && it <= selected.maxCapacity } ?: selected.minBatch
    val prices = sortedSetOf<Long>()
    unitPrice?.takeIf { it > 0 }?.let(prices::add)
    chosen.mapNotNull { offerPriceLimit(requests.getValue(it.wishId), it.text, selected.currency) }.forEach { limit ->
        if (limit > 0) prices.add(limit)
        if (limit < Long.MAX_VALUE) prices.add(limit + 1)
    }
    val points = if (prices.size < 2) emptyList() else prices.map { price ->
        val interest = offerInterest(output, selected, wishes, price)
        DemandPoint(price, interest.interest, minimumUnits, interest.interestWishIds)
    }
    val alternatives = candidates.map { option ->
        val served = byOption.getValue(option.id)
        DemandAlternative(option.id, option.name, served.size, served.map { it.wishId }, candidates.filter { it.id != option.id }.map { other ->
            val otherIds = byOption.getValue(other.id).map { it.wishId }.toSet()
            DemandDifference(other.name, served.filter { it.wishId !in otherIds })
        })
    }
    return DemandExplorer(selected.name, selected.currency, minimumUnits, selectedCounts.interest,
        chosen.count { it.price.status == PriceEvidenceStatus.MISSING },
        chosen.count { it.price.status == PriceEvidenceStatus.AMBIGUOUS },
        chosen.count { it.price.status == PriceEvidenceStatus.EXPLICITLY_UNBOUNDED },
        points, chosen, alternatives,
        if (points.isEmpty()) "A price-demand series needs at least two distinct candidate prices supported by quoted limits or the offer price." else null)
}

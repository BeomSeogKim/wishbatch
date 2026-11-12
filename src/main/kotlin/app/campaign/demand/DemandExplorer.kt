package app.campaign.demand

import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.port.AnalysisOutput
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
    fun compatible(option: Option): List<DemandWish> = output.verdicts.filter {
        it.optionId == option.id && it.verdict == CompatibilityVerdict.COMPATIBLE && it.wishId in requests
    }.map { it.wishId }.distinct().sortedBy(UUID::toString).map { id ->
        DemandWish(id, sources.getValue(id).text, quotedPriceLimit(requests.getValue(id), sources.getValue(id).text, option.currency))
    }
    val byOption = candidates.associate { it.id to compatible(it) }
    val chosen = byOption.getValue(selected.id)
    val priced = chosen.filter { it.price.status == PriceEvidenceStatus.QUOTED_LIMIT ||
        it.price.status == PriceEvidenceStatus.EXPLICITLY_UNBOUNDED }
    val minimumUnits = minimum?.takeIf { it >= selected.minBatch && it <= selected.maxCapacity } ?: selected.minBatch
    val prices = sortedSetOf(1L)
    unitPrice?.takeIf { it > 0 }?.let(prices::add)
    chosen.mapNotNull { it.price.maximumMinor }.forEach { limit ->
        if (limit > 0) prices.add(limit)
        if (limit < Long.MAX_VALUE) prices.add(limit + 1)
    }
    val points = prices.map { price ->
        val ids = priced.filter { it.price.maximumMinor == null || it.price.maximumMinor >= price }.map { it.wishId }
        DemandPoint(price, ids.size, minimumUnits, ids)
    }
    val alternatives = candidates.map { option ->
        val served = byOption.getValue(option.id)
        DemandAlternative(option.id, option.name, served.size, served.map { it.wishId }, candidates.filter { it.id != option.id }.map { other ->
            val otherIds = byOption.getValue(other.id).map { it.wishId }.toSet()
            DemandDifference(other.name, served.filter { it.wishId !in otherIds })
        })
    }
    return DemandExplorer(selected.name, selected.currency, minimumUnits, chosen.size,
        chosen.count { it.price.status == PriceEvidenceStatus.MISSING },
        chosen.count { it.price.status == PriceEvidenceStatus.AMBIGUOUS },
        chosen.count { it.price.status == PriceEvidenceStatus.EXPLICITLY_UNBOUNDED },
        points, chosen, alternatives)
}

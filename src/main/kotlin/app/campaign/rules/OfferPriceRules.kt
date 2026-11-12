package app.campaign.rules

import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Option
import app.campaign.model.WishKind
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalyzedWish

data class OfferPriceCheck(
    val compatible: Int,
    val depends: Int,
    val excludedByPrice: List<AnalyzedWish>,
) {
    val fit: Int get() = compatible + depends
}

/**
 * Rechecks validated request verdicts at an exact minor-unit price in the option's currency.
 * Equal limits still fit; absent limits and limits in another currency remain unaffected.
 * Conditional wishes retain their other unresolved conditions after the price check.
 */
fun offerPriceCheck(output: AnalysisOutput, option: Option, unitPrice: Long): OfferPriceCheck {
    val requests = output.wishes.filter { it.kind == WishKind.REQUEST }.associateBy { it.wishId }
    val candidates = output.verdicts.filter {
        it.optionId == option.id && it.wishId in requests && it.verdict != CompatibilityVerdict.INCOMPATIBLE
    }
    val excluded = candidates.mapNotNull { verdict ->
        requests.getValue(verdict.wishId).takeIf { wish ->
            wish.maxUnitPrice?.let { it.currency == option.currency && it.minorUnits < unitPrice } == true
        }
    }
    val excludedIds = excluded.map { it.wishId }.toSet()
    val remaining = candidates.filter { it.wishId !in excludedIds }
    return OfferPriceCheck(
        compatible = remaining.count { it.verdict == CompatibilityVerdict.COMPATIBLE },
        depends = remaining.count { it.verdict == CompatibilityVerdict.CONDITIONAL },
        excludedByPrice = excluded,
    )
}

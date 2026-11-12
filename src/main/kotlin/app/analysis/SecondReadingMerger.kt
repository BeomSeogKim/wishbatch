package app.analysis

import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.port.AnalysisOutput
import app.campaign.port.SecondReadingStatus
import app.campaign.port.SecondReading
import app.campaign.port.WishReadingComparison
import app.campaign.port.WishVerdict

internal object SecondReadingMerger {
    fun merge(options: List<Option>, wishes: List<Wish>, first: AnalysisOutput, second: AnalysisOutput): AnalysisOutput {
        val firstWishes = first.wishes.associateBy { it.wishId }
        val secondWishes = second.wishes.associateBy { it.wishId }
        val firstVerdicts = first.verdicts.associateBy { it.wishId to it.optionId }
        val secondVerdicts = second.verdicts.associateBy { it.wishId to it.optionId }
        val compared = wishes.filter { it.id in firstWishes && it.id in secondWishes }
        val comparisons = compared.flatMap { wish ->
            options.map { option ->
                WishReadingComparison(wish.id, option.id, firstWishes.getValue(wish.id).kind,
                    secondWishes.getValue(wish.id).kind, firstVerdicts[wish.id to option.id]?.verdict,
                    secondVerdicts[wish.id to option.id]?.verdict)
            }
        }
        val disagreements = comparisons.filter { it.needsYourEyes }.map { it.wishId }.toSet() +
            compared.filter { firstWishes.getValue(it.id).kind != secondWishes.getValue(it.id).kind }.map { it.id }
        val retained = wishes.filter { it.id in firstWishes }
        val incomplete = retained.filter { it.id !in secondWishes }.map { it.id }
        val mergedWishes = retained.map { wish ->
            val left = firstWishes.getValue(wish.id)
            val right = secondWishes[wish.id] ?: return@map left
            if (left.kind == WishKind.REQUEST) left else right
        }
        val requestIds = mergedWishes.filter { it.kind == WishKind.REQUEST }.map { it.wishId }.toSet()
        val comparedVerdicts = comparisons.filter { it.wishId in requestIds }.map { comparison ->
            if (comparison.needsYourEyes) {
                WishVerdict(comparison.wishId, comparison.optionId, CompatibilityVerdict.CONDITIONAL,
                    "Needs your eyes: the readings disagree.")
            } else firstVerdicts.getValue(comparison.wishId to comparison.optionId)
        }
        val comparedIds = compared.map { it.id }.toSet()
        val verdicts = comparedVerdicts + first.verdicts.filter { it.wishId !in comparedIds }
        return first.copy(wishes = mergedWishes, verdicts = verdicts,
            secondReading = SecondReading(compared.size - disagreements.size, compared.size, comparisons, wishes.size > 1,
                if (incomplete.isEmpty()) SecondReadingStatus.COMPLETE else SecondReadingStatus.INCOMPLETE, incomplete))
    }
}

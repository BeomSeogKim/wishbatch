package app.campaign.nextbatch

import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.port.AnalysisOutput
import java.util.UUID

data class NextBatchEvidence(val wishId: UUID, val quotes: List<String>)
data class NextBatchCandidate(val option: Option, val evidence: List<NextBatchEvidence>)
data class NextBatch(val hasProposal: Boolean, val candidates: List<NextBatchCandidate>)

fun nextBatch(output: AnalysisOutput, options: List<Option>, visibleWishes: List<Wish>): NextBatch {
    val selected = output.proposal?.optionId ?: return NextBatch(false, emptyList())
    if (options.none { it.id == selected } || selected !in output.optionIds) return NextBatch(false, emptyList())
    val visible = visibleWishes.filterNot { it.hidden }.associateBy { it.id }
    val requests = output.wishes.filter { it.kind == WishKind.REQUEST && it.wishId in visible }
        .associateBy { it.wishId }
    val unserved = output.verdicts.filter {
        it.optionId == selected && it.verdict == CompatibilityVerdict.INCOMPATIBLE && it.wishId in requests
    }.map { it.wishId }.toSet()
    val candidates = options.filter { it.id != selected && it.id in output.optionIds }.map { option ->
        val ids = output.verdicts.filter {
            it.optionId == option.id && it.verdict == CompatibilityVerdict.COMPATIBLE && it.wishId in unserved
        }.map { it.wishId }.toSet().sortedBy { it.toString() }
        NextBatchCandidate(option, ids.map { id ->
            val text = visible.getValue(id).text
            val quotes = requests.getValue(id).needs.map { it.quote }.filter { it.isNotBlank() && text.contains(it) }.distinct()
            NextBatchEvidence(id, quotes)
        })
    }.filter { it.evidence.isNotEmpty() }
        .sortedWith(compareByDescending<NextBatchCandidate> { it.evidence.size }.thenBy { it.option.name }.thenBy { it.option.id.toString() })
    return NextBatch(true, candidates)
}

package app.campaign.explanation

import app.campaign.model.CompatibilityVerdict
import app.campaign.model.WishKind
import app.campaign.port.AnalysisOutput
import app.campaign.review.WishReviewService
import app.campaign.review.reviewedAnalysis
import app.campaign.rules.OptionWishCounts
import app.campaign.rules.optionWishCounts
import app.campaign.store.AnalysisRunRepository
import app.campaign.store.WishRepository
import app.campaign.workspace.SeedCatalog
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

data class BatchEvidence(val counts: OptionWishCounts, val quotes: List<String>, val isSeed: Boolean)

@Service
@Transactional(readOnly = true)
class BatchExplanationQuery(
    private val runs: AnalysisRunRepository,
    private val wishes: WishRepository,
    private val seed: SeedCatalog,
    private val mapper: JsonMapper,
    private val reviews: WishReviewService,
) {
    fun evidence(workspaceId: UUID, runId: UUID, optionId: UUID): BatchEvidence? {
        val run = runs.findByIdAndWorkspaceId(runId, workspaceId) ?: return null
        val original = mapper.readValue(run.resultJson, AnalysisOutput::class.java)
        val output = reviewedAnalysis(original, reviews.history(workspaceId, runId))
        val counts = optionWishCounts(output).singleOrNull { it.optionId == optionId } ?: return null
        val proposal = output.proposal?.takeIf { it.optionId == optionId }
        val requests = output.wishes.filter { it.kind == WishKind.REQUEST }.map { it.wishId }.toSet()
        val compatible = output.verdicts.filter {
            it.optionId == optionId && it.verdict == CompatibilityVerdict.COMPATIBLE && it.wishId in requests
        }.map { it.wishId }.toSet()
        val seedTexts = seed.scenarios.flatMap { it.wishes }.groupBy({ seed.wishId(workspaceId, it.key) }, { it.text })
        val visible = wishes.findByWorkspaceId(workspaceId).filterNot { it.hidden }.associateBy { it.id }
        val quotes = proposal?.rationale.orEmpty().mapNotNull { item ->
            if (item.wishId !in compatible) return@mapNotNull null
            val wish = visible[item.wishId] ?: return@mapNotNull null
            val originals = seedTexts[item.wishId] ?: return@mapNotNull null
            if (wish.text !in originals || item.quote.isBlank() || !wish.text.contains(item.quote)) return@mapNotNull null
            item.quote
        }.distinct().take(3)
        return BatchEvidence(counts, quotes, run.isSeed)
    }
}

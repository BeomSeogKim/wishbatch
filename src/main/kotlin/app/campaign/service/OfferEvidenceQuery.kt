package app.campaign.service

import app.campaign.model.CompatibilityVerdict
import app.campaign.model.WishKind
import app.campaign.port.AnalysisOutput
import app.campaign.review.WishReviewService
import app.campaign.review.reviewedAnalysis
import app.campaign.rules.optionWishCounts
import app.campaign.store.AnalysisRunRepository
import app.campaign.store.OfferRepository
import app.campaign.store.WishRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

data class OfferEvidenceQuote(val wishId: UUID, val quote: String, val displayName: String?)
data class OfferEvidence(val compatibleWishCount: Int, val quotes: List<OfferEvidenceQuote>)

@Service
@Transactional(readOnly = true)
class OfferEvidenceQuery(
    private val offers: OfferRepository,
    private val runs: AnalysisRunRepository,
    private val wishes: WishRepository,
    private val mapper: JsonMapper,
    private val reviews: WishReviewService,
) {
    fun findByOffer(workspaceId: UUID, offerId: UUID): OfferEvidence? {
        val offer = offers.findByIdAndWorkspaceId(offerId, workspaceId) ?: return null
        val run = runs.findByIdAndWorkspaceId(offer.analysisRunId, workspaceId) ?: return null
        val original = mapper.readValue(run.resultJson, AnalysisOutput::class.java)
        val output = reviewedAnalysis(original, reviews.history(workspaceId, run.id))
        val proposal = output.proposal?.takeIf { it.optionId == offer.optionId } ?: return null
        val count = optionWishCounts(output).singleOrNull { it.optionId == offer.optionId } ?: return null
        val requests = output.wishes.filter { it.kind == WishKind.REQUEST }.map { it.wishId }.toSet()
        val compatible = output.verdicts.filter {
            it.optionId == offer.optionId && it.verdict == CompatibilityVerdict.COMPATIBLE && it.wishId in requests
        }.map { it.wishId }.toSet()
        val visible = wishes.findByWorkspaceId(workspaceId).filterNot { it.hidden }.associateBy { it.id }
        val candidates = proposal.rationale.map { it.wishId to it.quote } +
            output.wishes.flatMap { wish -> wish.needs.map { wish.wishId to it.quote } }
        val quotes = candidates.mapNotNull { (id, quote) ->
            val wish = visible[id] ?: return@mapNotNull null
            if (id !in compatible || quote.isBlank() || !wish.text.contains(quote)) return@mapNotNull null
            OfferEvidenceQuote(id, quote, wish.displayName)
        }.distinctBy { it.wishId }.take(3)
        return quotes.takeIf { it.isNotEmpty() }?.let { OfferEvidence(count.compatible, it) }
    }
}

package app.campaign.service

import app.campaign.port.AnalysisOutput
import app.campaign.review.WishReviewService
import app.campaign.review.reviewedAnalysis
import app.campaign.rules.OfferInterest
import app.campaign.store.AnalysisRunRepository
import app.campaign.store.OfferRepository
import app.campaign.store.WishRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

data class OfferEvidenceQuote(val wishId: UUID, val quote: String)
data class OfferEvidence(val counts: OfferInterest, val quotes: List<OfferEvidenceQuote>)

@Service
@Transactional(readOnly = true)
class OfferEvidenceQuery(
    private val offers: OfferRepository,
    private val runs: AnalysisRunRepository,
    private val wishes: WishRepository,
    private val mapper: JsonMapper,
    private val reviews: WishReviewService,
    private val interestQuery: OfferInterestQuery,
) {
    fun findByOffer(workspaceId: UUID, offerId: UUID): OfferEvidence? {
        val offer = offers.findByIdAndWorkspaceId(offerId, workspaceId) ?: return null
        val run = runs.findByIdAndWorkspaceId(offer.analysisRunId, workspaceId) ?: return null
        val original = mapper.readValue(run.resultJson, AnalysisOutput::class.java)
        val output = reviewedAnalysis(original, reviews.history(workspaceId, run.id))
        val proposal = output.proposal?.takeIf { it.optionId == offer.optionId } ?: return null
        val count = interestQuery.read(workspaceId, run.id, offer.optionId, offer.unitPrice) ?: return null
        val compatible = count.interestWishIds.toSet()
        val visible = wishes.findByWorkspaceId(workspaceId).filterNot { it.hidden }.associateBy { it.id }
        val candidates = proposal.rationale.map { it.wishId to it.quote } +
            output.wishes.flatMap { wish -> wish.needs.map { wish.wishId to it.quote } }
        val quotes = candidates.mapNotNull { (id, quote) ->
            val wish = visible[id] ?: return@mapNotNull null
            if (id !in compatible || quote.isBlank() || !wish.text.contains(quote)) return@mapNotNull null
            OfferEvidenceQuote(id, quote)
        }.groupBy { it.wishId }.values.map { quotes -> quotes.maxBy { it.quote.length } }.take(3)
        return quotes.takeIf { it.isNotEmpty() }?.let { OfferEvidence(count, it) }
    }
}

package app.campaign.service

import app.campaign.port.AnalysisOutput
import app.campaign.review.WishReviewService
import app.campaign.review.reviewedAnalysis
import app.campaign.rules.OfferInterest
import app.campaign.rules.offerInterest
import app.campaign.store.AnalysisRunRepository
import app.campaign.store.OptionRepository
import app.campaign.store.WishRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@Service
@Transactional(readOnly = true)
class OfferInterestQuery(
    private val analyses: AnalysisRunRepository,
    private val options: OptionRepository,
    private val wishes: WishRepository,
    private val reviews: WishReviewService,
    private val mapper: JsonMapper,
) {
    fun read(workspaceId: UUID, runId: UUID, optionId: UUID, unitPrice: Long): OfferInterest? {
        val run = analyses.findByIdAndWorkspaceId(runId, workspaceId) ?: return null
        val option = options.findByIdAndWorkspaceId(optionId, workspaceId) ?: return null
        val original = try {
            mapper.readValue(run.resultJson, AnalysisOutput::class.java)
        } catch (_: tools.jackson.core.JacksonException) { return null }
        val effective = reviewedAnalysis(original, reviews.history(workspaceId, run.id))
        return offerInterest(effective, option, wishes.findByWorkspaceId(workspaceId), unitPrice)
    }
}

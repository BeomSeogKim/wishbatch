package app.web.explanation

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.explanation.BatchExplanationQuery
import app.campaign.model.CampaignState
import app.campaign.service.CampaignQueries
import app.campaign.service.CampaignView
import app.campaign.service.ServiceResult
import app.campaign.workspace.WorkspaceService
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import java.math.BigDecimal
import java.util.UUID

@Controller
class BatchExplanationController(
    private val workspaces: WorkspaceService,
    private val campaigns: CampaignQueries,
    private val evidence: BatchExplanationQuery,
    private val analyses: AnalysisRunService,
) {
    @GetMapping("/w/{token}/how-this-batch-was-chosen")
    fun show(@PathVariable token: String, model: Model, response: HttpServletResponse): String {
        val workspace = workspaces.findByToken(token) ?: return notFound(response)
        val campaign = when (val result = campaigns.currentCampaign(token)) {
            is ServiceResult.Success -> result.value
            is ServiceResult.Rejected -> null
        } ?: return notFound(response)
        return render(token, workspace.id, campaign, model)
    }

    @GetMapping("/w/{token}/campaigns/{campaignId}/how-this-batch-was-chosen")
    fun showCampaign(
        @PathVariable token: String, @PathVariable campaignId: UUID, model: Model, response: HttpServletResponse,
    ): String {
        val workspace = workspaces.findByToken(token) ?: return notFound(response)
        val campaign = when (val result = campaigns.campaignById(token, campaignId)) {
            is ServiceResult.Success -> result.value
            is ServiceResult.Rejected -> return notFound(response)
        }
        if (campaign.state == CampaignState.DRAFT) return notFound(response)
        return render(token, workspace.id, campaign, model)
    }

    private fun render(token: String, workspaceId: UUID, campaign: CampaignView, model: Model): String {
        val offer = campaign.offer
        model.addAttribute("token", token)
        model.addAttribute("offerTitle", offer.title)
        model.addAttribute("spec", offer.specText)
        model.addAttribute("variants", offer.chosenVariants)
        model.addAttribute("price", "${offer.currency} ${BigDecimal.valueOf(offer.unitPrice, 2).toPlainString()}")
        model.addAttribute("delivery", offer.deliveryTerms)
        model.addAttribute("region", offer.shipRegion)
        model.addAttribute("minimum", offer.minBatch)
        model.addAttribute("capacity", offer.capacity)
        model.addAttribute("counts", campaign.counts)
        model.addAttribute("state", when (campaign.state) {
            CampaignState.DRAFT -> "Draft: the offer is not open for pledges."
            CampaignState.OPEN -> "Open: fans can seek authorization holds while the offer is available."
            CampaignState.AWAITING_CONFIRMATION -> "Awaiting confirmation: the maker must confirm before capture."
            CampaignState.SETTLING -> "Settling: PayPal payments and any required reversals are being processed."
            CampaignState.FUNDED -> "Funded: the campaign previously reached its paid minimum."
            CampaignState.NOT_FUNDED -> "Not funded: refunds and hold releases are attempted; some may still be pending or need attention."
        })
        model.addAttribute("paidShortfall", campaign.state == CampaignState.FUNDED && campaign.counts.paid < offer.minBatch)
        model.addAttribute("paymentAttention", campaign.needsAttention.isNotEmpty())
        model.addAttribute("evidence", evidence.evidence(workspaceId, offer.analysisRunId, offer.optionId))
        val attachedRun = analyses.find(token, offer.analysisRunId)?.run
        model.addAttribute("analysisRecordedAt", attachedRun?.createdAt)
        model.addAttribute("analysisModel", attachedRun?.model)
        return "explanation/index"
    }

    private fun notFound(response: HttpServletResponse): String {
        response.status = HttpServletResponse.SC_NOT_FOUND
        return "error/not-found"
    }
}

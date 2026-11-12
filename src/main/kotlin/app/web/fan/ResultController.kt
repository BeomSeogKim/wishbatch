package app.web.fan

import app.campaign.model.CampaignReason
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

@Controller
class ResultController(private val workspaces: WorkspaceService, private val campaigns: CampaignQueries) {
    @GetMapping("/w/{token}/result")
    fun result(@PathVariable token: String, model: Model, response: HttpServletResponse): String {
        if (workspaces.findByToken(token) == null) return fanNotFound(response)
        val campaign = when (val result = campaigns.currentCampaign(token)) {
            is ServiceResult.Success -> result.value
            is ServiceResult.Rejected -> return fanNotFound(response)
        }
        model.addAttribute("token", token)
        addCampaignResult(model, campaign)
        return "fan/result"
    }
}

internal fun hasResult(campaign: CampaignView): Boolean =
    campaign.state in setOf(CampaignState.FUNDED, CampaignState.NOT_FUNDED)

internal fun addCampaignResult(model: Model, campaign: CampaignView?) {
    model.addAttribute("campaign", campaign)
    model.addAttribute("resultAvailable", campaign?.let { hasResult(it) } ?: false)
    model.addAttribute("resultTitle", when (campaign?.state) {
        CampaignState.FUNDED -> "Funded"
        CampaignState.NOT_FUNDED -> "Not funded"
        else -> "No final result yet"
    })
    val reason = when (campaign?.reason) {
        CampaignReason.THRESHOLD_NOT_MET -> "The authorized quantity did not reach the minimum before closing."
        CampaignReason.CANCELLED_BY_MAKER -> "The maker cancelled this campaign."
        CampaignReason.CONFIRMATION_EXPIRED -> "The maker did not confirm production before the deadline."
        CampaignReason.CAPTURE_SHORTFALL -> "Too few payments completed to meet the minimum batch."
        null -> if (campaign?.state == CampaignState.NOT_FUNDED) "The campaign did not fund."
            else "The campaign has not reached a final outcome."
    }
    model.addAttribute("resultReason", when (campaign?.state) {
        CampaignState.FUNDED -> if (campaign.counts.paid < campaign.offer.minBatch) {
            "The paid minimum was met at settlement. Current completed payments are below the minimum and need attention."
        } else {
            "The paid minimum was met at settlement. Current payment status may change."
        }
        CampaignState.NOT_FUNDED -> "$reason Refunds and hold releases are attempted; unresolved payments still need attention."
        else -> reason
    })
}

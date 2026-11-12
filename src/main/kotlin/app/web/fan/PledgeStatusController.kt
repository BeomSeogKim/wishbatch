package app.web.fan

import app.campaign.model.PledgeState
import app.campaign.service.CampaignQueries
import app.campaign.service.ServiceResult
import app.campaign.workspace.WorkspaceService
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.MessageSource
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

@Controller
class PledgeStatusController(
    private val workspaces: WorkspaceService,
    private val campaigns: CampaignQueries,
    private val messages: MessageSource,
) {
    @GetMapping("/w/{token}/pledges/{id}")
    fun pledge(@PathVariable token: String, @PathVariable id: String, model: Model, response: HttpServletResponse): String {
        if (workspaces.findByToken(token) == null) return fanNotFound(response)
        val pledgeId = parsePledgeId(id) ?: return fanNotFound(response)
        val lookup = when (val result = campaigns.lookupPledge(token, pledgeId)) {
            is ServiceResult.Success -> result.value
            is ServiceResult.Rejected -> return fanNotFound(response)
        }
        model.addAttribute("token", token)
        model.addAttribute("pledge", lookup.pledge)
        model.addAttribute("amount", displayMoney(lookup.pledge.amount, lookup.pledge.currency))
        model.addAttribute("sentence", pledgeSentence(lookup.pledge.state, messages))
        model.addAttribute("next", when (lookup.pledge.state) {
            PledgeState.CREATED -> "Check this status before trying again if you already approved in PayPal. Otherwise, return to the offer to try again."
            PledgeState.ABANDONED -> "Return to the offer to try again if it is still open."
            PledgeState.AUTHORIZED -> "Payment may be captured if the minimum is reached and the maker confirms before the deadline. Otherwise the hold will be released."
            PledgeState.CAPTURED -> "Check the campaign result for production and the maker's delivery terms. If too few payments complete, completed payments are refunded."
            PledgeState.CAPTURE_PENDING -> "Check this page again for PayPal's confirmed payment status. Pending payments do not count as paid."
            PledgeState.CAPTURE_FAILED -> "Any remaining hold must be released. Check this page again for the release status."
            PledgeState.VOIDED -> "No further payment is due for this pledge. Your bank or card determines when the hold disappears."
            PledgeState.REFUNDED -> "PayPal and your bank or card determine when the refund appears."
            PledgeState.REFUND_PENDING -> "Check this page again for the refund result."
            PledgeState.VOID_FAILED, PledgeState.REFUND_FAILED -> "The payment needs attention. Check this page again; release or refund has not been confirmed."
        })
        addCampaignResult(model, lookup.campaign)
        return "fan/pledge"
    }
}

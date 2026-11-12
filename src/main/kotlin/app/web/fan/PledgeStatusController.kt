package app.web.fan

import app.campaign.model.PledgeState
import app.campaign.outcome.outcomeStep
import app.campaign.service.CampaignService
import app.campaign.service.PledgeReferences
import app.campaign.service.Rejection
import app.campaign.service.CampaignQueries
import app.campaign.service.ServiceResult
import app.campaign.workspace.WorkspaceService
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.MessageSource
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
class PledgeStatusController(
    private val workspaces: WorkspaceService,
    private val campaigns: CampaignQueries,
    private val messages: MessageSource,
    private val service: CampaignService,
    private val references: PledgeReferences,
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
        model.addAttribute("canWithdraw", lookup.canWithdraw)
        model.addAttribute("amount", displayMoney(lookup.pledge.amount, lookup.pledge.currency))
        model.addAttribute("sentence", pledgeSentence(lookup.pledge.state, messages))
        model.addAttribute("outcomeStep", outcomeStep(lookup.pledge.state).name)
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

    @PostMapping("/w/{token}/pledges/{id}")
    fun withdraw(
        @PathVariable token: String,
        @PathVariable id: String,
        @RequestParam(defaultValue = "") action: String,
        @RequestParam(defaultValue = "false") confirmed: Boolean,
        redirect: RedirectAttributes,
        response: HttpServletResponse,
    ): String {
        if (workspaces.findByToken(token) == null) return fanNotFound(response)
        val pledgeId = parsePledgeId(id) ?: return fanNotFound(response)
        val ref = when (val result = references.find(token, pledgeId)) {
            is ServiceResult.Success -> result.value
            is ServiceResult.Rejected -> return fanNotFound(response)
        }
        if (action != "withdraw" || !confirmed) {
            redirect.addFlashAttribute("error", "Confirm that you want to withdraw this pledge.")
            return "redirect:/w/$token/pledges/$id"
        }
        when (val result = service.withdrawPledge(ref)) {
            is ServiceResult.Success -> redirect.addFlashAttribute("notice",
                "Hold released by PayPal. When the amount reappears depends on your bank or card.")
            is ServiceResult.Rejected -> {
                if (result.reason == Rejection.NOT_FOUND) return fanNotFound(response)
                redirect.addFlashAttribute("error", if (result.reason == Rejection.INVALID_STATE)
                    "This pledge cannot be withdrawn. Withdrawal is available only for an authorized hold while the campaign is open, before settlement or closing."
                else "PayPal has not confirmed the hold release. Check this page again for the payment status.")
            }
        }
        return "redirect:/w/$token/pledges/$id"
    }
}

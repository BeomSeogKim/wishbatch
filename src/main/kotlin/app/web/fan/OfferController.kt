package app.web.fan

import app.campaign.rules.CapacityDecision
import app.campaign.service.CampaignQueries
import app.campaign.service.OfferEvidenceQuery
import app.campaign.service.ServiceResult
import app.campaign.workspace.WorkspaceService
import app.campaign.terms.TermsSealQuery
import app.paypal.PayPalProperties
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.math.BigDecimal

@Controller("fanOfferController")
class OfferController(
    private val workspaces: WorkspaceService,
    private val campaigns: CampaignQueries,
    private val paypal: PayPalProperties,
    private val evidence: OfferEvidenceQuery,
    private val terms: TermsSealQuery,
) {
    @GetMapping("/w/{token}/offer")
    fun offer(
        @PathVariable token: String,
        @RequestParam(defaultValue = "1") quantity: Int,
        model: Model,
        response: HttpServletResponse,
    ): String {
        val workspace = workspaces.findByToken(token) ?: return fanNotFound(response)
        val campaign = when (val result = campaigns.currentCampaign(token)) {
            is ServiceResult.Success -> result.value
            is ServiceResult.Rejected -> return fanNotFound(response)
        }
        model.addAttribute("token", token)
        model.addAttribute("campaign", campaign)
        model.addAttribute("quantity", quantity)
        model.addAttribute("campaignOpen", campaigns.isOpenForPledges(token))
        model.addAttribute("resultAvailable", campaign?.let { hasResult(it) } ?: false)
        model.addAttribute("paypalReady", paypal.configured)
        model.addAttribute("paypalClientId", if (paypal.configured) paypal.clientId else "")
        campaign?.let { model.addAttribute("unitPrice", displayMoney(it.offer.unitPrice, it.offer.currency)) }
        model.addAttribute("termsSeal", campaign?.let { terms.findByOffer(workspace.id, it.offer.id) })
        model.addAttribute("offerEvidence", campaign?.let {
            evidence.findByOffer(workspace.id, it.offer.id)?.let(OfferEvidenceView::from)
        })
        when (val result = campaigns.quote(token, quantity)) {
            is ServiceResult.Success -> {
                model.addAttribute("quote", result.value)
                if (result.value.allowed) {
                    model.addAttribute("total", displayMoney(result.value.amount, result.value.currency))
                } else {
                    model.addAttribute("quoteError", when (result.value.decision) {
                        CapacityDecision.INVALID_QUANTITY -> "Choose a quantity within this offer's limit."
                        else -> "There is not enough capacity for this quantity. Try a smaller quantity."
                    })
                }
            }
            is ServiceResult.Rejected -> model.addAttribute("quoteError", "This offer is not available for pledges.")
        }
        return "fan/offer"
    }
}

internal fun displayMoney(amount: Long, currency: String): String =
    "$currency ${BigDecimal.valueOf(amount, 2).toPlainString()}"

internal fun fanNotFound(response: HttpServletResponse): String {
    response.status = HttpServletResponse.SC_NOT_FOUND
    return "error/not-found"
}

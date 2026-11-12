package app.web.maker

import app.campaign.charts.CampaignChartsQuery
import app.campaign.model.Campaign
import app.campaign.model.CampaignState
import app.campaign.model.CampaignReason
import app.campaign.model.PledgeState
import app.campaign.rules.thresholdMet
import app.campaign.service.CampaignRef
import app.campaign.service.CampaignService
import app.campaign.service.CampaignView
import app.campaign.service.ServiceResult
import app.campaign.service.TimelineEntry
import app.campaign.store.AnalysisRunRepository
import app.campaign.store.CampaignRepository
import app.campaign.store.OfferRepository
import app.campaign.workspace.WorkspaceNotFoundException
import app.campaign.workspace.WorkspaceService
import app.web.layout.HumanDisplay
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.math.BigInteger
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** Read-only workspace lookup until the campaign boundary exposes a current-campaign query. */
@Component
class MakerCampaignLookup(private val offers: OfferRepository, private val campaigns: CampaignRepository) {
    fun current(workspaceId: UUID): CampaignRef? {
        val offerIds = offers.findByWorkspaceId(workspaceId).map { it.id }
        if (offerIds.isEmpty()) return null
        return campaigns.findByOfferIdIn(offerIds).filter { it.openedAt != null }
            .maxWithOrNull(compareBy<Campaign> { it.state !in setOf(CampaignState.FUNDED, CampaignState.NOT_FUNDED) }
                .thenBy { it.openedAt })?.let { CampaignRef(workspaceId, it.id) }
    }
}

@Component("makerDisplay")
class MakerDisplay(private val humanDisplay: HumanDisplay = HumanDisplay()) {
    fun money(amount: Long, currency: String): String = humanDisplay.money(amount, currency)
    fun money(amount: BigInteger, currency: String): String = humanDisplay.money(amount, currency)
    fun words(value: Any): String = humanDisplay.state(value)
    fun catalogLabel(value: String): String = when (value) {
        "postcard-set" -> "Postcard set"
        "art-print" -> "Art print"
        "sticker-sheet" -> "Sticker sheet"
        "designCount" -> "Designs in a set"
        "cut" -> "Cut style"
        else -> value.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace('-', ' ').replace('_', ' ').replaceFirstChar { it.titlecase(Locale.US) }
    }
    fun time(value: Instant?): String = humanDisplay.time(value)
    fun recentEvents(events: List<TimelineEntry>): List<TimelineEntry> = events
        .filter { it.endpoint == null && !it.type.startsWith("PAYPAL_") }.takeLast(5).reversed()
    fun shortId(value: Any?): String = humanDisplay.shortId(value)
    fun detail(value: String): String = Regex("\\b[A-Z][A-Z_]+\\b").replace(value) {
        if (it.value in stateTokens) words(it.value) else it.value
    }.replace(" -> ", " → ")
    private val stateTokens = (CampaignState.entries.map { it.name } +
        CampaignReason.entries.map { it.name } + PledgeState.entries.map { it.name }).toSet()

    fun pledge(state: PledgeState): String = when (state) {
        PledgeState.CREATED -> "Approval not completed; no hold counted."
        PledgeState.ABANDONED -> "Approval abandoned; no hold counted."
        PledgeState.VOID_FAILED -> "Hold release could not be confirmed. Needs attention."
        PledgeState.REFUND_FAILED -> "Refund could not be completed. Needs attention."
        else -> ""
    }
}

@Controller
class CampaignController(
    private val workspaces: WorkspaceService,
    private val lookup: MakerCampaignLookup,
    private val campaigns: CampaignService,
    private val analyses: AnalysisRunRepository,
    private val charts: CampaignChartsQuery,
    @Value("\${app.campaign.demo-simulation-enabled:false}") private val simulationEnabled: Boolean,
) {
    @GetMapping("/w/{token}/maker/campaign")
    fun campaign(@PathVariable token: String, @RequestParam(defaultValue = "false") review: Boolean, model: Model): String {
        val view = read(token, model)
        populate(model, view)
        model.addAttribute("review", review)
        model.addAttribute("simulationEnabled", simulationEnabled)
        model.addAttribute("charts", view?.let { charts.read(token, it.id) })
        return "maker/campaign"
    }

    @GetMapping("/w/{token}/maker/result")
    fun result(@PathVariable token: String, model: Model): String {
        val view = read(token, model)
        populate(model, view)
        if (view != null) {
            // Aggregate displayed completed rows exactly, including histories larger than a Long total.
            model.addAttribute("paidAmount", total(view, PledgeState.CAPTURED))
            model.addAttribute("refundedAmount", total(view, PledgeState.REFUNDED))
            if (view.result.productionReleased) {
                val workspace = workspaces.findByToken(token) ?: throw WorkspaceNotFoundException()
                val batch = campaigns.fundedBatch(CampaignRef(workspace.id, view.id))
                model.addAttribute("batch", (batch as? ServiceResult.Success)?.value)
            }
        }
        return "maker/result"
    }

    @PostMapping("/w/{token}/maker/campaign")
    fun act(
        @PathVariable token: String,
        @RequestParam action: String,
        @RequestParam fields: Map<String, String>,
        redirect: RedirectAttributes,
    ): String {
        val workspace = workspaces.findByToken(token) ?: throw WorkspaceNotFoundException()
        val ref = lookup.current(workspace.id) ?: throw WorkspaceNotFoundException()
        if (fields["campaignId"] != ref.campaignId.toString()) {
            redirect.addFlashAttribute("error", "The campaign has changed. Review the current dashboard.")
            return "redirect:/w/$token/maker/campaign"
        }
        if (action == "review") return "redirect:/w/$token/maker/campaign?review=true"
        val result = when (action) {
            "cancel" -> campaigns.cancel(ref)
            "simulate" -> campaigns.simulateNextCaptureFailure(ref)
            "retry" -> campaigns.reconcile(ref)
            "confirm" -> {
                val current = (campaigns.readCampaign(ref) as? ServiceResult.Success)?.value
                    ?: throw WorkspaceNotFoundException()
                val summary = current.confirmation
                if (fields["acknowledged"] != "true" || fields["pledgeCount"] != summary.pledgeCount.toString() ||
                    fields["amount"] != summary.amount.toString() || fields["units"] != summary.units.toString()) {
                    redirect.addFlashAttribute("error", "Review the current pledge count and total, then confirm the payment consequences.")
                    return "redirect:/w/$token/maker/campaign?review=true"
                }
                campaigns.confirmProduction(ref)
            }
            else -> {
                redirect.addFlashAttribute("error", "Choose an available campaign action.")
                return "redirect:/w/$token/maker/campaign"
            }
        }
        return when (result) {
            is ServiceResult.Rejected -> {
                redirect.addFlashAttribute("error", "The action could not complete: ${result.reason.name.lowercase().replace('_', ' ')}. Refresh the dashboard for the current state.")
                "redirect:/w/$token/maker/campaign"
            }
            is ServiceResult.Success -> {
                if (action == "simulate") redirect.addFlashAttribute("notice", "Simulation armed: the next capture will fail without a provider call.")
                if (action == "retry") redirect.addFlashAttribute("notice", "Payment recheck requested. Unresolved payments stay visible; provider errors may delay the next attempt.")
                "redirect:/w/$token/maker/${if (result.value.state in terminalStates) "result" else "campaign"}"
            }
        }
    }

    private fun read(token: String, model: Model): CampaignView? {
        val workspace = workspaces.findByToken(token) ?: throw WorkspaceNotFoundException()
        val ref = lookup.current(workspace.id)
        if (ref == null) {
            model.addAttribute("makerStep", if (analyses.findByWorkspaceId(workspace.id).isEmpty()) 1 else 3)
            return null
        }
        return (campaigns.readCampaign(ref) as? ServiceResult.Success)?.value ?: throw WorkspaceNotFoundException()
    }

    private fun populate(model: Model, view: CampaignView?) {
        if (view != null) model.addAttribute("makerStep", if (view.state in terminalStates) 6 else 5)
        model.addAttribute("campaign", view)
        model.addAttribute("pledgeLabels", view?.pledges?.mapIndexed { index, pledge ->
            pledge.id to "Pledge ${index + 1}"
        }?.toMap().orEmpty())
        model.addAttribute("terminal", view?.state in terminalStates)
        val cancellable = view?.state in setOf(CampaignState.OPEN, CampaignState.AWAITING_CONFIRMATION)
        model.addAttribute("canCancel", cancellable)
        val canConfirm = cancellable && view != null && thresholdMet(view.counts.authorizedHolds, view.offer.minBatch)
        model.addAttribute("canConfirm", canConfirm)
        model.addAttribute("nextAction", when {
            view == null -> "OFFER"
            view.state in terminalStates -> "RESULT"
            canConfirm -> "CONFIRM"
            view.state == CampaignState.OPEN -> "SHARE"
            else -> "REFRESH"
        })
        model.addAttribute("showDeadline", view?.state in setOf(CampaignState.OPEN, CampaignState.AWAITING_CONFIRMATION))
    }

    private fun total(view: CampaignView, state: PledgeState): BigInteger = view.pledges.filter { it.state == state }
        .fold(BigInteger.ZERO) { sum, pledge -> sum + BigInteger.valueOf(pledge.amount) }

    @ExceptionHandler(WorkspaceNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(): String = "error/not-found"

    private val terminalStates = setOf(CampaignState.FUNDED, CampaignState.NOT_FUNDED)
}

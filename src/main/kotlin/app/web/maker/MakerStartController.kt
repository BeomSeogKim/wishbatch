package app.web.maker

import app.campaign.model.CampaignState
import app.campaign.service.CampaignService
import app.campaign.service.ServiceResult
import app.campaign.store.AnalysisRunRepository
import app.campaign.workspace.WishNotFoundException
import app.campaign.workspace.WorkspaceNotFoundException
import app.campaign.workspace.WorkspaceService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import java.util.UUID

@Controller
class MakerStartController(
    private val workspaces: WorkspaceService,
    private val campaignLookup: MakerCampaignLookup,
    private val campaigns: CampaignService,
    private val analyses: AnalysisRunRepository,
) {

    @PostMapping("/demo")
    fun startDemo(@RequestParam(defaultValue = "illustrator") scenario: String): String {
        if (scenario !in setOf("illustrator", "ceramicist", "textile")) throw InvalidScenarioException()
        return "redirect:/w/${workspaces.create(scenario).token}/maker"
    }

    @GetMapping("/w/{token}/maker")
    fun home(@PathVariable token: String, model: Model): String {
        val workspace = workspaces.findByToken(token) ?: throw WorkspaceNotFoundException()
        val campaign = campaignLookup.current(workspace.id)?.let { ref ->
            (campaigns.readCampaign(ref) as? ServiceResult.Success)?.value
        }
        val run = analyses.findByWorkspaceId(workspace.id).maxByOrNull { it.createdAt }
        val terminal = campaign?.state in setOf(CampaignState.FUNDED, CampaignState.NOT_FUNDED)
        model.addAttribute("workspace", workspace)
        model.addAttribute("makerCampaign", campaign)
        model.addAttribute("offerRun", run?.id)
        model.addAttribute("makerStep", when {
            terminal -> 6
            campaign != null -> 5
            run != null -> 3
            else -> 1
        })
        model.addAttribute("fanOfferUrl", campaign?.let { "/w/$token/offer" })
        return "maker/home"
    }

    @GetMapping("/w/{token}/maker/options")
    fun options(@PathVariable token: String, model: Model): String {
        val options = workspaces.listOptions(token)
        model.addAttribute("options", options)
        model.addAttribute("unitCosts", options.associate { option ->
            val currency = Currency.getInstance(option.currency)
            val amount = BigDecimal.valueOf(option.unitCost, currency.defaultFractionDigits)
            val format = NumberFormat.getCurrencyInstance(Locale.US).apply { this.currency = currency }
            option.id to "${option.currency} ${format.format(amount)}"
        })
        return "maker/options"
    }

    @GetMapping("/w/{token}/maker/wishes")
    fun wishes(@PathVariable token: String, model: Model): String {
        model.addAttribute("wishes", workspaces.listWishes(token))
        model.addAttribute("visibleWishCount", workspaces.listWishes(token, includeHidden = false).size)
        return "maker/wishes"
    }

    @PostMapping("/w/{token}/maker/wishes", params = ["wishId", "hidden"])
    fun setWishHidden(
        @PathVariable token: String,
        @RequestParam wishId: UUID,
        @RequestParam hidden: Boolean,
    ): String {
        workspaces.setWishHidden(token, wishId, hidden)
        return "redirect:/w/$token/maker/wishes"
    }

    @PostMapping("/w/{token}/maker/wishes", params = ["comments"])
    fun importWishes(
        @PathVariable token: String,
        @RequestParam comments: String,
        redirectAttributes: RedirectAttributes,
    ): String {
        redirectAttributes.addFlashAttribute("importResult", workspaces.addImportedWishes(token, comments))
        return "redirect:/w/$token/maker/wishes"
    }

    @ExceptionHandler(WorkspaceNotFoundException::class, WishNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(): String = "error/not-found"
}

@ResponseStatus(HttpStatus.BAD_REQUEST)
class InvalidScenarioException : RuntimeException()

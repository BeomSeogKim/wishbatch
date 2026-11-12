package app.web.maker

import app.campaign.analysisrun.AnalysisRunResult
import app.campaign.analysisrun.AnalysisRunService
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.port.AnalysisFailure
import app.campaign.port.WishNeed
import app.campaign.rules.optionWishCounts
import app.campaign.workspace.WorkspaceNotFoundException
import app.campaign.workspace.WorkspaceService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

data class ProposalWish(val wish: Wish, val needs: List<WishNeed>, val reason: String)
data class ProposalWishGroup(val label: String, val reason: String, val wishes: List<ProposalWish>)

@Controller
class ProposalController(private val workspaces: WorkspaceService, private val runs: AnalysisRunService) {

    @GetMapping("/w/{token}/maker/proposal")
    fun proposal(@PathVariable token: String, model: Model): String {
        val stored = runs.latest(token)
        val options = workspaces.listOptions(token).associateBy { it.id }
        val wishes = workspaces.listWishes(token).associateBy { it.id }
        model.addAttribute("stored", stored)
        model.addAttribute("options", options)
        model.addAttribute("sourceWishes", wishes)
        model.addAttribute("hasVisibleWishes", wishes.values.any { !it.hidden })
        model.addAttribute("proposalUnavailable", stored?.output?.proposal == null)
        model.addAttribute("liveRunCount", runs.liveRunCount(token))
        if (stored != null) {
            val output = stored.output
            val option = output.proposal?.let { options[it.optionId] }
            model.addAttribute("proposedOption", option)
            model.addAttribute("optionCounts", optionWishCounts(output))
            model.addAttribute("proposedCounts", optionWishCounts(output).singleOrNull { it.optionId == option?.id })
            if (option != null) {
                val currency = Currency.getInstance(option.currency)
                val amount = BigDecimal.valueOf(option.unitCost, currency.defaultFractionDigits)
                val format = NumberFormat.getCurrencyInstance(Locale.US).apply { this.currency = currency }
                model.addAttribute("unitCost", "${option.currency} ${format.format(amount)}")
            }
            val verdicts = output.verdicts.filter { it.optionId == option?.id }.associateBy { it.wishId }
            val rows = output.wishes.mapNotNull { analyzed ->
                wishes[analyzed.wishId]?.let { wish ->
                    analyzed.wishId to ProposalWish(wish, analyzed.needs,
                        verdicts[analyzed.wishId]?.reason ?: "Praise, noise, or instructions rather than a product request.")
                }
            }.toMap()
            model.addAttribute("compatibleWishes", output.verdicts.filter {
                it.optionId == option?.id && it.verdict == CompatibilityVerdict.COMPATIBLE
            }.mapNotNull { rows[it.wishId] })
            model.addAttribute("dependsWishes", output.verdicts.filter {
                it.optionId == option?.id && it.verdict == CompatibilityVerdict.CONDITIONAL
            }.mapNotNull { rows[it.wishId] })
            model.addAttribute("notServedGroups", output.notServed.map { group ->
                ProposalWishGroup(group.label, group.reason, group.wishIds.mapNotNull { rows[it] })
            })
            model.addAttribute("nonRequestWishes", output.wishes.filter { it.kind == WishKind.NOT_A_REQUEST }
                .mapNotNull { rows[it.wishId] })
        }
        return "maker/proposal"
    }

    @PostMapping("/w/{token}/maker/proposal")
    fun analyze(@PathVariable token: String, redirect: RedirectAttributes): String {
        when (val result = runs.run(token)) {
            is AnalysisRunResult.Completed -> redirect.addFlashAttribute("analysisNotice", "Analysis complete. Review the evidence before choosing your offer.")
            is AnalysisRunResult.Failed -> redirect.addFlashAttribute("analysisError", failureMessage(result.reason))
            AnalysisRunResult.NoVisibleWishes -> redirect.addFlashAttribute("analysisError", "There are no visible wishes to analyze. Add or unhide a wish, then try again.")
        }
        return "redirect:/w/$token/maker/proposal"
    }

    @ExceptionHandler(WorkspaceNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(): String = "error/not-found"

    private fun failureMessage(reason: AnalysisFailure): String = when (reason) {
        AnalysisFailure.NOT_CONFIGURED -> "Live analysis is not configured. A model API key is needed to re-run. Your last valid analysis is still shown."
        AnalysisFailure.TIMEOUT -> "The model took too long to respond. Try again later. Your last valid analysis is still shown."
        AnalysisFailure.INVALID_JSON -> "The model returned a response we could not read. Try again. Your last valid analysis is still shown."
        AnalysisFailure.INVALID_OUTPUT -> "The model response did not pass the evidence checks. Try again. Your last valid analysis is still shown."
        AnalysisFailure.PROVIDER_FAILURE -> "The model service is unavailable. Try again later. Your last valid analysis is still shown."
        AnalysisFailure.RUN_LIMIT_REACHED -> "The analysis limit has been reached for this workspace or the model service. Your last valid analysis is still shown."
    }
}

package app.web.maker

import app.campaign.analysisrun.AnalysisRunResult
import app.campaign.analysisrun.AnalysisRunService
import app.campaign.demand.exploreDemand
import app.campaign.boundary.summarizeBoundary
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.port.AnalysisFailure
import app.campaign.port.WishNeed
import app.campaign.review.InvalidWishReviewException
import app.campaign.review.WishReviewOverride
import app.campaign.review.WishReviewService
import app.campaign.rules.optionWishCounts
import app.campaign.workspace.WorkspaceNotFoundException
import app.campaign.workspace.WorkspaceService
import app.campaign.workbench.WishWorkbench
import app.campaign.workbench.WorkbenchFilter
import app.campaign.workbench.wishWorkbenchRows
import app.web.workbench.addWorkbenchAttributes
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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

data class ProposalWish(
    val wish: Wish,
    val needs: List<WishNeed>,
    val reason: String,
    val needsYourEyes: Boolean = false,
    val decisions: List<WishReviewOverride> = emptyList(),
    val readingLabels: List<String> = emptyList(),
)
data class ProposalGroupSummary(val label: String, val key: String, val count: Int, val representative: ProposalWish?)

@Controller
class ProposalController(
    private val workspaces: WorkspaceService,
    private val runs: AnalysisRunService,
    private val display: MakerDisplay,
    private val reviews: WishReviewService,
) {

    @GetMapping("/w/{token}/maker/proposal")
    fun proposal(@PathVariable token: String, @RequestParam(required = false) run: UUID?, model: Model): String {
        val stored = if (run == null) runs.latest(token)
            else runs.find(token, run) ?: throw WorkspaceNotFoundException()
        val options = workspaces.listOptions(token).associateBy { it.id }
        val wishes = workspaces.listWishes(token).associateBy { it.id }
        model.addAttribute("stored", stored)
        model.addAttribute("boundary", summarizeBoundary(stored))
        model.addAttribute("options", options)
        model.addAttribute("sourceWishes", wishes)
        model.addAttribute("hasVisibleWishes", wishes.values.any { !it.hidden })
        model.addAttribute("proposalUnavailable", stored?.output?.proposal == null)
        model.addAttribute("liveRunCount", runs.liveRunCount(token))
        val reviewRows = wishWorkbenchRows(
            wishes.values.toList(), options.values.toList(), stored?.output, stored?.flags.orEmpty(),
            stored?.overrides.orEmpty(), stored?.originalOutput,
        ).map { row -> row.copy(labels = row.labels.map { it.copy(reason = catalogText(it.reason)) }) }
        addWorkbenchAttributes(model, WishWorkbench(stored, options.values.toList(), reviewRows),
            WorkbenchFilter(optionId = stored?.output?.proposal?.optionId))
        model.addAttribute("workbenchReturnTo", "proposal")
        if (stored != null) {
            val output = stored.output
            val secondReading = stored.originalOutput.secondReading
            model.addAttribute("secondReading", secondReading)
            model.addAttribute("agreementRate", secondReading?.let {
                if (it.comparedWishCount == 0) "No wishes compared" else
                    "${BigDecimal.valueOf(it.agreedWishCount.toLong()).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(it.comparedWishCount.toLong()), 1, java.math.RoundingMode.HALF_UP)}%"
            })
            val option = output.proposal?.let { options[it.optionId] }
            model.addAttribute("proposedOption", option)
            model.addAttribute("optionCounts", optionWishCounts(output))
            model.addAttribute("proposedCounts", optionWishCounts(output).singleOrNull { it.optionId == option?.id })
            if (option != null) {
                model.addAttribute("demand", exploreDemand(output, options.values.toList(), wishes.values.toList(), option.id))
                model.addAttribute("optionFormat", display.catalogLabel(option.format))
                model.addAttribute("optionAttributes", option.fixedAttributes.mapKeys { display.catalogLabel(it.key) }
                    .mapValues { display.catalogLabel(it.value) })
                model.addAttribute("suggestedVariants", output.proposal.suggestedVariants.mapKeys { display.catalogLabel(it.key) }
                    .mapValues { display.catalogLabel(it.value) })
                val currency = Currency.getInstance(option.currency)
                val amount = BigDecimal.valueOf(option.unitCost, currency.defaultFractionDigits)
                val format = NumberFormat.getCurrencyInstance(Locale.US).apply { this.currency = currency }
                model.addAttribute("unitCost", "${option.currency} ${format.format(amount)}")
            }
            val verdicts = output.verdicts.filter { it.optionId == option?.id }.associateBy { it.wishId }
            val rows = output.wishes.mapNotNull { analyzed ->
                wishes[analyzed.wishId]?.let { wish ->
                    val decisions = stored.overrides.filter { it.wishId == wish.id }
                        .groupBy { it.optionId }.values.map { history -> history.maxBy { it.revision } }
                    val comparisons = secondReading?.comparisons.orEmpty().filter { it.wishId == wish.id }
                    val fallbackReason = if (option == null && analyzed.kind == WishKind.REQUEST)
                        "Choose an option below to record your decision."
                    else "Praise, noise, or instructions rather than a product request."
                    analyzed.wishId to ProposalWish(wish, analyzed.needs.map { it.copy(value = catalogText(it.value)) },
                        catalogText(verdicts[analyzed.wishId]?.reason ?: fallbackReason),
                        comparisons.any { comparison -> comparison.needsYourEyes &&
                            decisions.none { it.optionId == comparison.optionId } },
                        decisions,
                        comparisons.filter { it.optionId == option?.id || it.needsYourEyes }.flatMap { listOf(
                            "${options[it.optionId]?.name} · First reading: ${readingLabel(it.firstKind, it.firstVerdict)}",
                            "${options[it.optionId]?.name} · Second reading: ${readingLabel(it.secondKind, it.secondVerdict)}",
                        ) })
                }
            }.toMap()
            model.addAttribute("allWishes", rows.values.toList())
            if (option != null) {
                val counts = optionWishCounts(output).single { it.optionId == option.id }
                fun representative(verdict: CompatibilityVerdict): ProposalWish? = output.verdicts
                    .firstOrNull { it.optionId == option.id && it.verdict == verdict }?.let { rows[it.wishId] }
                model.addAttribute("groupSummaries", listOf(
                    ProposalGroupSummary("Compatible", "COMPATIBLE", counts.compatible, representative(CompatibilityVerdict.COMPATIBLE)),
                    ProposalGroupSummary("Depends", "CONDITIONAL", counts.depends, representative(CompatibilityVerdict.CONDITIONAL)),
                    ProposalGroupSummary("Not served", "INCOMPATIBLE", counts.notServed, representative(CompatibilityVerdict.INCOMPATIBLE)),
                    ProposalGroupSummary("Not a request", "NOT_A_REQUEST", counts.notARequest,
                        output.wishes.firstOrNull { it.kind == WishKind.NOT_A_REQUEST }?.let { rows[it.wishId] }),
                ))
            }

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

    @PostMapping("/w/{token}/maker/proposal/review")
    fun review(
        @PathVariable token: String,
        @RequestParam run: UUID,
        @RequestParam wish: UUID,
        @RequestParam option: UUID,
        @RequestParam verdict: CompatibilityVerdict,
        @RequestParam reason: String,
        redirect: RedirectAttributes,
    ): String {
        reviews.decide(token, run, wish, option, verdict, reason)
        redirect.addFlashAttribute("analysisNotice", "Your decision is saved. Counts now include your review; the original readings are preserved.")
        return "redirect:/w/$token/maker/proposal?run=$run#wish-$wish"
    }

    @ExceptionHandler(InvalidWishReviewException::class)
    fun invalidReview(): ResponseEntity<String> = ResponseEntity.badRequest()
        .body("Choose Compatible or Not served and provide a reason of 1 to 500 characters.")

    private fun readingLabel(kind: WishKind, verdict: CompatibilityVerdict?): String =
        if (kind == WishKind.NOT_A_REQUEST) "Not a request" else when (verdict) {
            CompatibilityVerdict.COMPATIBLE -> "Compatible"
            CompatibilityVerdict.CONDITIONAL -> "Depends"
            CompatibilityVerdict.INCOMPATIBLE -> "Not served"
            null -> "Not classified"
        }

    private fun catalogText(value: String): String =
        Regex("postcard-set|art-print|sticker-sheet|designCount|die-cut").replace(value) { display.catalogLabel(it.value) }

    private fun failureMessage(reason: AnalysisFailure): String = when (reason) {
        AnalysisFailure.NOT_CONFIGURED -> "Live analysis is not configured. A model API key is needed to re-run. Your last valid analysis is still shown."
        AnalysisFailure.TIMEOUT -> "The model took too long to respond. Try again later. Your last valid analysis is still shown."
        AnalysisFailure.INVALID_JSON -> "The model returned a response we could not read. Try again. Your last valid analysis is still shown."
        AnalysisFailure.INVALID_OUTPUT -> "The model response did not pass the evidence checks. Try again. Your last valid analysis is still shown."
        AnalysisFailure.PROVIDER_FAILURE -> "The model service is unavailable. Try again later. Your last valid analysis is still shown."
        AnalysisFailure.RUN_LIMIT_REACHED -> "The analysis limit has been reached for this workspace or the model service. Your last valid analysis is still shown."
    }
}

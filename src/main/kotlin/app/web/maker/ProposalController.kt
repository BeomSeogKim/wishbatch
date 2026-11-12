package app.web.maker

import app.campaign.analysisrun.AnalysisRunResult
import app.campaign.analysisrun.AnalysisRunService
import app.campaign.demand.exploreDemand
import app.campaign.followup.FollowupService
import app.campaign.gloss.GlossStatus
import app.campaign.gloss.WishGlossService
import app.campaign.replies.InvalidReplyEvidenceException
import app.campaign.replies.ReplyService
import app.campaign.boundary.summarizeBoundary
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.port.AnalysisFailure
import app.campaign.port.WishNeed
import app.campaign.review.InvalidWishReviewException
import app.campaign.review.WishReviewOverride
import app.campaign.review.WishReviewService
import app.campaign.rules.offerInterest
import app.campaign.rules.OfferInterest
import app.campaign.service.CampaignQueries
import app.campaign.service.ServiceResult
import app.campaign.workspace.SeedCatalog
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
data class ProposalGroupSummary(val label: String, val key: String, val count: Int, val representative: ProposalWish?, val wishIds: List<UUID> = emptyList())
data class ProposalOptionInterest(val optionId: UUID, val unitPrice: Long, val counts: OfferInterest)

data class ProposalHelper(val title: String, val state: String, val action: String, val url: String)

@Controller
class ProposalController(
    private val workspaces: WorkspaceService,
    private val runs: AnalysisRunService,
    private val display: MakerDisplay,
    private val reviews: WishReviewService,
    private val followups: FollowupService,
    private val replies: ReplyService,
    private val glosses: WishGlossService,
    private val campaigns: CampaignQueries,
    private val seed: SeedCatalog,
) {

    @GetMapping("/w/{token}/maker/proposal")
    fun proposal(@PathVariable token: String, @RequestParam(required = false) run: UUID?, model: Model): String {
        val published = (campaigns.currentCampaign(token) as? ServiceResult.Success)?.value
        val selectedRun = run ?: published?.offer?.analysisRunId
        val stored = if (selectedRun == null) runs.latest(token)
            else runs.find(token, selectedRun) ?: throw WorkspaceNotFoundException()
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
            model.addAttribute("proposalHelpers", helperCards(token, stored.run.id))
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
            val optionCounts = options.values.mapNotNull { candidate ->
                val price = published?.offer?.takeIf { it.analysisRunId == stored.run.id && it.optionId == candidate.id }
                    ?.unitPrice ?: seed.listPrice(candidate) ?: return@mapNotNull null
                ProposalOptionInterest(candidate.id, price, offerInterest(output, candidate, wishes.values.toList(), price))
            }
            val proposed = optionCounts.singleOrNull { it.optionId == option?.id }
            model.addAttribute("optionCounts", optionCounts)
            model.addAttribute("proposedCounts", proposed?.counts)
            model.addAttribute("interestPrice", proposed?.let { display.money(it.unitPrice, option!!.currency) })
            if (option != null) {
                model.addAttribute("demand", exploreDemand(output, options.values.toList(), wishes.values.toList(), option.id, proposed?.unitPrice))
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
                val counts = proposed?.counts ?: OfferInterest()
                fun representative(ids: List<UUID>): ProposalWish? = ids.firstNotNullOfOrNull { rows[it] }
                model.addAttribute("groupSummaries", listOf(
                    ProposalGroupSummary("Interested in this offer", "COMPATIBLE", counts.interest, representative(counts.interestWishIds), counts.interestWishIds),
                    ProposalGroupSummary("Depend on a condition", "CONDITIONAL", counts.conditional, representative(counts.conditionalWishIds), counts.conditionalWishIds),
                    ProposalGroupSummary("Excluded by price", "PRICE_EXCLUDED", counts.excludedByPrice, representative(counts.excludedByPriceWishIds), counts.excludedByPriceWishIds),
                    ProposalGroupSummary("Not served", "INCOMPATIBLE", counts.notServed, representative(counts.notServedWishIds), counts.notServedWishIds),
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

    private fun helperCards(token: String, runId: UUID): List<ProposalHelper> {
        val questions = followups.makerPage(token)
        val questionState = if (questions.runId != runId) "Counts unavailable · latest analysis only"
            else if (questions.failure != null) "Draft attempt failed · ${questions.questions.size} saved questions"
            else if (!questions.drafted) "Not drafted · ${questions.questions.size} saved questions"
            else "${questions.questions.size} questions · ${questions.questions.count { it.approved }} approved"
        val replyState = try {
            val page = replies.maker(token, runId)
            if (page.runId != runId || page.status in setOf("REFRESH_REQUIRED", "REANALYSIS_REQUIRED"))
                "Counts unavailable · historical or changed evidence"
            else "${page.groups.size} groups · ${page.groups.count { it.draft != null }} saved replies · " +
                "${page.groups.count { it.approved }} approved · ${page.status.lowercase().replace('_', ' ')}"
        } catch (_: InvalidReplyEvidenceException) {
            "Counts unavailable · historical or changed evidence"
        }
        val rows = glosses.read(token, runId).rows
        val glossAction = when {
            rows.any { !it.current } -> "source evidence unavailable"
            rows.any { it.saved?.status == null && (it.saved?.attempts ?: 0) > 0 } -> "attempt failed; review reading aids"
            rows.any { it.saved?.status == GlossStatus.UNCERTAIN } -> "uncertain results need review"
            rows.any { it.saved?.status == null } -> "${rows.count { it.saved?.status == null }} unchecked"
            rows.isEmpty() -> "no visible source wishes"
            else -> "all visible wishes checked"
        }
        val glossState = if (rows.isNotEmpty() && rows.none { it.current }) "Reading aids unavailable · $glossAction"
            else "${rows.count { it.saved?.status == GlossStatus.TRANSLATED }} translations ready · $glossAction"
        return listOf(
            ProposalHelper("Follow-up questions", questionState, "Review follow-up questions for the latest analysis", "/w/$token/maker/questions"),
            ProposalHelper("Replies", replyState, "Review replies", "/w/$token/maker/replies?run=$runId"),
            ProposalHelper("English reading aids", glossState, "Review reading aids", "/w/$token/maker/glosses?run=$runId"),
        )
    }

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

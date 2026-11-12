package app.web.maker

import app.campaign.model.AnalysisRun
import app.campaign.demand.exploreDemand
import app.campaign.model.NeedAttribute
import app.campaign.model.Option
import app.campaign.offerdraft.InvalidOfferDraftException
import app.campaign.offerdraft.OfferDraftService
import app.campaign.port.AnalysisOutput
import app.campaign.review.WishReviewService
import app.campaign.review.reviewedAnalysis
import app.campaign.rules.MoneyInput
import app.campaign.rules.OfferViolation
import app.campaign.rules.offerInterest
import app.campaign.rules.offerPriceLimit
import app.campaign.service.CampaignRef
import app.campaign.service.CampaignQueries
import app.campaign.service.CampaignService
import app.campaign.service.DraftOffer
import app.campaign.service.ServiceResult
import app.campaign.store.AnalysisRunRepository
import app.campaign.workspace.WorkspaceNotFoundException
import app.campaign.workspace.WorkspaceService
import app.web.layout.HumanDisplay
import app.campaign.workspace.SeedCatalog
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID

@Controller
class OfferController(
    private val workspaces: WorkspaceService,
    private val analyses: AnalysisRunRepository,
    private val mapper: ObjectMapper,
    private val campaigns: CampaignService,
    private val queries: CampaignQueries,
    private val display: MakerDisplay,
    private val humanDisplay: HumanDisplay,
    private val reviews: WishReviewService,
    private val drafts: OfferDraftService,
    private val catalog: SeedCatalog,
) {
    @GetMapping("/w/{token}/maker/offer")
    fun offer(@PathVariable token: String, @RequestParam(required = false) run: UUID?, model: Model): String {
        val workspace = workspaces.findByToken(token) ?: throw WorkspaceNotFoundException()
        val published = (queries.currentCampaign(token) as? ServiceResult.Success)?.value
        val selectedRun = run ?: published?.offer?.analysisRunId
        val analysis = if (selectedRun == null) analyses.findByWorkspaceId(workspace.id).maxByOrNull { it.createdAt }
            else analyses.findByIdAndWorkspaceId(selectedRun, workspace.id) ?: throw WorkspaceNotFoundException()
        val output = analysis?.let(::output)
        val option = output?.proposal?.let { proposal -> workspaces.listOptions(token).find { it.id == proposal.optionId } }
        model.addAttribute("makerStep", if (option != null) 4 else if (analysis != null) 3 else 1)
        model.addAttribute("analysis", analysis)
        model.addAttribute("output", output)
        model.addAttribute("option", option)
        if (option != null && !model.containsAttribute("fields")) {
            model.addAttribute("fields", defaults(option, output!!, analysis!!).toMutableMap().apply {
                if (published?.offer?.analysisRunId == analysis.id && published.offer.optionId == option.id) {
                    put("unitPrice", MoneyInput.formatMinorUnits(published.offer.unitPrice))
                }
            })
        }
        if (option != null) {
            model.addAttribute("reviewedDraft", try {
                drafts.page(token, analysis!!.id).takeUnless { it.published }?.draft
                    ?.takeIf { it.reviewedAt != null && it.failure == null }
            } catch (_: InvalidOfferDraftException) { null })
            @Suppress("UNCHECKED_CAST")
            val fields = model.getAttribute("fields") as Map<String, String>
            model.addAttribute("durationLabel", when (fields["duration"]) {
                "15" -> "15 minutes"
                "120" -> "2 hours"
                "1440" -> "24 hours"
                "2880" -> "48 hours"
                else -> "Check campaign duration"
            })
            val price = fields["unitPrice"]?.let(MoneyInput::parseMinorUnits)?.takeIf { it > 0 }
            model.addAttribute("priceBelowCost", price != null && price < option.unitCost)
            model.addAttribute("priceCheck", price?.let { priceView(token, analysis!!, output!!, option, it) })
            model.addAttribute("demand", exploreDemand(output!!, workspaces.listOptions(token),
                workspaces.listWishes(token), option.id, price, fields["minBatch"]?.toIntOrNull()))
            model.addAttribute("demandRunId", analysis!!.id)
        }
        return "maker/offer"
    }

    @GetMapping("/w/{token}/maker/offer/price-check")
    @ResponseBody
    fun priceCheck(@PathVariable token: String, @RequestParam run: UUID, @RequestParam unitPrice: String): OfferPriceView {
        val workspace = workspaces.findByToken(token) ?: throw WorkspaceNotFoundException()
        val analysis = analyses.findByIdAndWorkspaceId(run, workspace.id) ?: throw WorkspaceNotFoundException()
        val output = output(analysis) ?: throw WorkspaceNotFoundException()
        val option = output.proposal?.let { proposal -> workspaces.listOptions(token).find { it.id == proposal.optionId } }
            ?: throw WorkspaceNotFoundException()
        val price = MoneyInput.parseMinorUnits(unitPrice)?.takeIf { it > 0 }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a positive unit price with up to two decimal places.")
        return priceView(token, analysis, output, option, price)
    }

    @PostMapping("/w/{token}/maker/offer")
    fun publish(
        @PathVariable token: String,
        @RequestParam fields: Map<String, String>,
        redirect: RedirectAttributes,
    ): String {
        val workspace = workspaces.findByToken(token) ?: throw WorkspaceNotFoundException()
        val runId = fields["run"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw WorkspaceNotFoundException()
        val analysis = analyses.findByIdAndWorkspaceId(runId, workspace.id) ?: throw WorkspaceNotFoundException()
        val output = output(analysis)
        val option = output?.proposal?.let { proposal -> workspaces.listOptions(token).find { it.id == proposal.optionId } }
        val errors = linkedMapOf<String, String>()
        if (fields["action"] == "use-reviewed-draft") {
            val updated = fields.toMutableMap()
            try {
                val draftId = fields["draft"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: throw InvalidOfferDraftException()
                updated.putAll(drafts.reviewedCopy(token, runId, draftId, fields["draftSourceHash"].orEmpty()))
                updated["reviewedDraftApplied"] = "true"
            } catch (_: InvalidOfferDraftException) {
                errors["offer"] = "This reviewed draft is unavailable or stale. Review the current source before using it."
            }
            redirect.addFlashAttribute("fields", updated)
            if (errors.isNotEmpty()) redirect.addFlashAttribute("errors", errors)
            return "redirect:/w/$token/maker/offer?run=$runId"
        }
        if (option == null || output == null) {
            errors["offer"] = "This analysis has no available proposal. Review the evidence before creating an offer."
        } else {
            val price = fields["unitPrice"]?.let(MoneyInput::parseMinorUnits)
            if (price != null && price > 0) {
                redirect.addFlashAttribute("priceCheck", priceView(token, analysis, output, option, price))
            }
            if (fields["action"] == "check-price") {
                if (price == null || price <= 0) errors["unitPrice"] = "Enter a positive unit price with up to two decimal places."
                redirect.addFlashAttribute("fields", fields)
                if (errors.isNotEmpty()) redirect.addFlashAttribute("errors", errors)
                return "redirect:/w/$token/maker/offer?run=$runId"
            }
            if (fields["canDeliver"] != "true") errors["canDeliver"] = "Confirm that you can produce and deliver this specification."
            output.questionsForMaker.indices.forEach { index ->
                if (fields["question.$index"] !in setOf("answered", "dismissed")) {
                    errors["question.$index"] = "Resolve this question: mark it answered in the specification or not applicable before publishing."
                }
            }
            val minimum = fields["minBatch"]?.toIntOrNull()
            val capacity = fields["capacity"]?.toIntOrNull()
            val quantity = fields["maxQtyPerPledge"]?.toIntOrNull()
            val minutes = fields["duration"]?.toLongOrNull()
            if (price == null || price <= 0) errors["unitPrice"] = "Enter a positive unit price with up to two decimal places."
            mapOf("minBatch" to minimum, "capacity" to capacity,
                "maxQtyPerPledge" to quantity, "duration" to minutes).forEach { (name, value) ->
                if (value == null) errors[name] = "Enter a whole number within the displayed limits."
            }
            for (name in listOf("title", "specText", "deliveryTerms")) {
                if (fields[name].isNullOrBlank()) errors[name] = "This field is required."
            }
            if (errors.isEmpty()) {
                val duration = runCatching { Duration.ofMinutes(minutes!!) }.getOrNull()
                if (duration == null) errors["duration"] = "Choose one of the four campaign durations."
                else {
                    val command = DraftOffer(option.id, analysis.id, fields.getValue("title"), fields.getValue("specText"),
                        option.allowedVariants.keys.associateWith { fields["variant.$it"].orEmpty() }, price!!,
                        fields.getValue("deliveryTerms"), minimum!!, capacity!!, duration, quantity!!,
                        output.proposal!!.rationale.map { it.wishId }.distinct())
                    when (val draft = campaigns.createDraft(workspace.id, command)) {
                        is ServiceResult.Rejected -> addErrors(errors, draft)
                        is ServiceResult.Success -> when (val published = campaigns.publish(
                            CampaignRef(workspace.id, draft.value.campaignId), duration)) {
                            is ServiceResult.Rejected -> addErrors(errors, published)
                            is ServiceResult.Success -> {
                                if (draft.value.warnings.isNotEmpty()) redirect.addFlashAttribute("notice", "The unit price is below the option's unit cost.")
                                return "redirect:/w/$token/maker/campaign"
                            }
                        }
                    }
                }
            }
        }
        redirect.addFlashAttribute("fields", fields)
        redirect.addFlashAttribute("errors", errors)
        return "redirect:/w/$token/maker/offer?run=$runId"
    }

    private fun output(run: AnalysisRun): AnalysisOutput? = try {
        val original = mapper.readValue(run.resultJson, AnalysisOutput::class.java)
        reviewedAnalysis(original, reviews.history(run.workspaceId, run.id))
    } catch (_: tools.jackson.core.JacksonException) { null }

    private fun priceView(token: String, run: AnalysisRun, output: AnalysisOutput, option: Option, price: Long): OfferPriceView {
        val sources = workspaces.listWishes(token)
        val check = offerInterest(output, option, sources, price)
        return OfferPriceView(check.interest, check.conditional, check.excludedByPrice, check.notServed,
            check.interestWishIds, check.conditionalWishIds, check.excludedByPriceWishIds, check.notServedWishIds,
            output.wishes.filter { it.wishId in check.excludedByPriceWishIds }.map { wish ->
            val limit = offerPriceLimit(wish, sources.single { it.id == wish.wishId }.text, option.currency)!!
            ExcludedPriceWish(wish.wishId, wish.needs.filter { it.attribute == NeedAttribute.PRICE }.map { it.quote },
                humanDisplay.money(limit, option.currency), "/w/$token/maker/proposal?run=${run.id}#wish-${wish.wishId}")
        }, price, humanDisplay.money(price, option.currency), price < option.unitCost)
    }

    private fun defaults(option: Option, output: AnalysisOutput, run: AnalysisRun): Map<String, String> = buildMap {
        put("run", run.id.toString())
        put("title", option.name)
        put("specText", (listOf(display.catalogLabel(option.format)) + option.fixedAttributes.map { "${display.catalogLabel(it.key)}: ${it.value}" }).joinToString("\n"))
        put("unitPrice", catalog.listPrice(option)?.let(MoneyInput::formatMinorUnits).orEmpty())
        put("deliveryTerms", option.leadTimeText)
        put("minBatch", option.minBatch.toString())
        put("capacity", option.maxCapacity.toString())
        put("duration", "1440")
        put("maxQtyPerPledge", "3")
        option.allowedVariants.forEach { (key, values) ->
            put("variant.$key", output.proposal!!.suggestedVariants[key] ?: values.first())
        }
    }

    private fun addErrors(errors: MutableMap<String, String>, result: ServiceResult.Rejected) {
        errors["offer"] = when (result.reason) {
            app.campaign.service.Rejection.ACTIVE_CAMPAIGN -> "A campaign is already active. Open its dashboard before publishing another offer."
            else -> "The offer could not be published: ${result.reason.name.lowercase().replace('_', ' ')}."
        }
        result.validation?.violations?.forEach { violation ->
            val (field, text) = when (violation) {
                OfferViolation.INVALID_TITLE -> "title" to "Use a title from 1 to 127 characters."
                OfferViolation.MINIMUM_BELOW_OPTION -> "minBatch" to "Use at least the option's minimum batch."
                OfferViolation.MINIMUM_EXCEEDS_CAPACITY -> "minBatch" to "The minimum cannot exceed capacity."
                OfferViolation.NON_POSITIVE_CAPACITY, OfferViolation.CAPACITY_ABOVE_OPTION -> "capacity" to "Use a positive capacity no greater than the option's limit."
                OfferViolation.INVALID_DURATION -> "duration" to "Choose 15 minutes, 2 hours, 24 hours, or 48 hours."
                OfferViolation.NON_POSITIVE_PRICE -> "unitPrice" to "The unit price must be greater than zero."
                OfferViolation.QUANTITY_OUT_OF_RANGE -> "maxQtyPerPledge" to "Use a quantity from 1 to the offer capacity."
                OfferViolation.CURRENCY_MISMATCH -> "unitPrice" to "Use the option's currency."
            }
            errors[field] = text
        }
    }

    @ExceptionHandler(WorkspaceNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(): String = "error/not-found"
}

data class OfferPriceView(
    val interest: Int,
    val conditional: Int,
    val excludedByPrice: Int,
    val notServed: Int,
    val interestWishIds: List<UUID>,
    val conditionalWishIds: List<UUID>,
    val excludedByPriceWishIds: List<UUID>,
    val notServedWishIds: List<UUID>,
    val excludedWishes: List<ExcludedPriceWish>,
    val unitPrice: Long,
    val formattedPrice: String,
    val belowCost: Boolean,
)
data class ExcludedPriceWish(val wishId: UUID, val quotes: List<String>, val limit: String, val sourceUrl: String)

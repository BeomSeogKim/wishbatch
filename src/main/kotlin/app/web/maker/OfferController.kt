package app.web.maker

import app.campaign.model.AnalysisRun
import app.campaign.model.Option
import app.campaign.port.AnalysisOutput
import app.campaign.rules.OfferViolation
import app.campaign.service.CampaignRef
import app.campaign.service.CampaignService
import app.campaign.service.DraftOffer
import app.campaign.service.ServiceResult
import app.campaign.store.AnalysisRunRepository
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
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID

@Controller
class OfferController(
    private val workspaces: WorkspaceService,
    private val analyses: AnalysisRunRepository,
    private val mapper: ObjectMapper,
    private val campaigns: CampaignService,
) {
    @GetMapping("/w/{token}/maker/offer")
    fun offer(@PathVariable token: String, @RequestParam(required = false) run: UUID?, model: Model): String {
        val workspace = workspaces.findByToken(token) ?: throw WorkspaceNotFoundException()
        val analysis = if (run == null) analyses.findByWorkspaceId(workspace.id).maxByOrNull { it.createdAt }
            else analyses.findByIdAndWorkspaceId(run, workspace.id) ?: throw WorkspaceNotFoundException()
        val output = analysis?.let(::output)
        val option = output?.proposal?.let { proposal -> workspaces.listOptions(token).find { it.id == proposal.optionId } }
        model.addAttribute("makerStep", if (option != null) 4 else if (analysis != null) 3 else 1)
        model.addAttribute("analysis", analysis)
        model.addAttribute("output", output)
        model.addAttribute("option", option)
        if (option != null && !model.containsAttribute("fields")) {
            model.addAttribute("fields", defaults(option, output!!, analysis!!))
        }
        return "maker/offer"
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
        if (option == null || output == null) {
            errors["offer"] = "This analysis has no available proposal. Review the evidence before creating an offer."
        } else {
            if (fields["canDeliver"] != "true") errors["canDeliver"] = "Confirm that you can produce and deliver this specification."
            output.questionsForMaker.indices.forEach { index ->
                if (fields["question.$index"] !in setOf("answered", "dismissed")) {
                    errors["question.$index"] = "Answer this question in the specification or dismiss it."
                }
            }
            val price = fields["unitPrice"]?.toLongOrNull()
            val minimum = fields["minBatch"]?.toIntOrNull()
            val capacity = fields["capacity"]?.toIntOrNull()
            val quantity = fields["maxQtyPerPledge"]?.toIntOrNull()
            val minutes = fields["duration"]?.toLongOrNull()
            mapOf("unitPrice" to price, "minBatch" to minimum, "capacity" to capacity,
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
        mapper.readValue(run.resultJson, AnalysisOutput::class.java)
    } catch (_: tools.jackson.core.JacksonException) { null }

    private fun defaults(option: Option, output: AnalysisOutput, run: AnalysisRun): Map<String, String> = buildMap {
        put("run", run.id.toString())
        put("title", option.name)
        put("specText", (listOf(option.format) + option.fixedAttributes.map { "${it.key}: ${it.value}" }).joinToString("\n"))
        put("unitPrice", option.unitCost.toString())
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

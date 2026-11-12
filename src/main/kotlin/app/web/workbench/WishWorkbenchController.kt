package app.web.workbench

import app.campaign.workbench.WishWorkbenchService
import app.campaign.workbench.WishWorkbench
import app.campaign.workbench.WorkbenchRow
import app.campaign.workbench.WorkbenchFilter
import app.campaign.workbench.workbenchCounts
import app.campaign.gloss.WishGlossService
import app.campaign.model.CompatibilityVerdict
import app.campaign.workspace.WorkspaceNotFoundException
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
import java.util.UUID

@Controller
class WishWorkbenchController(
    private val workbenches: WishWorkbenchService,
    private val glosses: WishGlossService,
) {
    @GetMapping("/w/{token}/maker/proposal/wishes")
    fun page(
        @PathVariable token: String,
        @RequestParam(required = false) run: UUID?,
        @RequestParam(required = false) optionId: UUID?,
        @RequestParam(defaultValue = "") label: String,
        @RequestParam(defaultValue = "") q: String,
        @RequestParam(defaultValue = "") sort: String,
        model: Model,
    ): String {
        val workbench = workbenches.read(token, run)
        val filter = WorkbenchFilter(optionId, label, q.take(500), sort)
        addWorkbenchAttributes(model, workbench, filter, explicitSort = sort.isNotBlank())
        return "workbench/page"
    }

    @GetMapping("/w/{token}/maker/proposal/wishes/{wishId}")
    fun detail(
        @PathVariable token: String,
        @PathVariable wishId: UUID,
        @RequestParam(required = false) run: UUID?,
        model: Model,
    ): String {
        val workbench = workbenches.read(token, run)
        val row = workbench.rows.singleOrNull { it.id == wishId } ?: throw WorkspaceNotFoundException()
        val original = workbench.stored?.originalOutput
        model.addAttribute("workbench", workbench)
        model.addAttribute("row", row)
        model.addAttribute("firstWish", original?.wishes?.singleOrNull { it.wishId == wishId })
        model.addAttribute("firstVerdicts", original?.verdicts.orEmpty().filter { it.wishId == wishId }.associateBy { it.optionId })
        model.addAttribute("readings", original?.secondReading?.comparisons.orEmpty()
            .filter { it.wishId == wishId }.associateBy { it.optionId })
        model.addAttribute("gloss", workbench.stored?.let { stored ->
            glosses.read(token, stored.run.id).rows.singleOrNull { it.wishId == wishId }
        })
        return "workbench/detail"
    }

    @PostMapping("/w/{token}/maker/proposal/wishes/hide")
    fun hide(
        @PathVariable token: String,
        @RequestParam run: UUID,
        @RequestParam optionId: UUID,
        @RequestParam wishIds: List<UUID>,
        @RequestParam(defaultValue = "workbench") returnTo: String,
        redirect: RedirectAttributes,
    ): String {
        require(returnTo in setOf("workbench", "proposal"))
        workbenches.hide(token, run, optionId, wishIds)
        redirect.addFlashAttribute("workbenchNotice", "Selected wishes hidden. The stored analysis remains a snapshot.")
        return destination(token, run, optionId, returnTo)
    }

    @PostMapping("/w/{token}/maker/proposal/wishes/review")
    fun review(
        @PathVariable token: String,
        @RequestParam run: UUID,
        @RequestParam optionId: UUID,
        @RequestParam wishIds: List<UUID>,
        @RequestParam verdict: CompatibilityVerdict,
        @RequestParam reason: String,
        @RequestParam(defaultValue = "workbench") returnTo: String,
        redirect: RedirectAttributes,
    ): String {
        require(returnTo in setOf("workbench", "proposal"))
        workbenches.review(token, run, optionId, wishIds, verdict, reason)
        redirect.addFlashAttribute("workbenchNotice", "Maker decisions saved with reasons. Counts use your review; original readings are preserved.")
        return destination(token, run, optionId, returnTo)
    }

    private fun destination(token: String, run: UUID, optionId: UUID, returnTo: String): String =
        if (returnTo == "proposal") "redirect:/w/$token/maker/proposal?run=$run#wish-workbench"
        else "redirect:/w/$token/maker/proposal/wishes?run=$run&optionId=$optionId"

    @ExceptionHandler(WorkspaceNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(): String = "error/not-found"

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidSelection(model: Model): String {
        model.addAttribute("message", "Select wishes and an option from this run. Label decisions need Compatible or Not served and a reason of one to 500 characters.")
        return "workbench/invalid"
    }
}

fun addWorkbenchAttributes(
    model: Model,
    workbench: WishWorkbench,
    filter: WorkbenchFilter = WorkbenchFilter(),
    explicitSort: Boolean = false,
) {
    val effectiveFilter = if (filter.sort.isBlank() || filter.sort == "id" && !explicitSort) {
        filter.copy(sort = if (workbench.rows.any { it.flags.isNotEmpty() }) "flags" else "id")
    } else filter
    val rows = if (effectiveFilter.sort == "flags") {
        workbench.rows.sortedWith(compareByDescending<WorkbenchRow> { it.flags.isNotEmpty() }.thenBy { it.id })
    } else effectiveFilter.sorted(workbench.rows)
    val visible = rows.filter(effectiveFilter::matches)
    model.addAttribute("workbench", workbench)
    model.addAttribute("workbenchRows", rows)
    model.addAttribute("workbenchFilter", effectiveFilter)
    model.addAttribute("workbenchHasFlags", workbench.rows.any { it.flags.isNotEmpty() })
    model.addAttribute("visibleIds", visible.map { it.id }.toSet())
    model.addAttribute("workbenchReturnTo", "workbench")
    model.addAttribute("workbenchCountGroups", workbench.options.filter { filter.optionId == null || it.id == filter.optionId }
        .associate { option ->
            option.name to workbenchCounts(visible, option.id).mapKeys { (label, _) ->
                rows.flatMap { it.labels }.first { it.label == label }.displayLabel
            }
        })
}

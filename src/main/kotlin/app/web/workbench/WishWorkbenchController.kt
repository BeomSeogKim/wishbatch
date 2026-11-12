package app.web.workbench

import app.campaign.workbench.WishWorkbenchService
import app.campaign.workbench.WorkbenchFilter
import app.campaign.workbench.workbenchCounts
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
class WishWorkbenchController(private val workbenches: WishWorkbenchService) {
    @GetMapping("/w/{token}/maker/proposal/wishes")
    fun page(
        @PathVariable token: String,
        @RequestParam(required = false) run: UUID?,
        @RequestParam(required = false) optionId: UUID?,
        @RequestParam(defaultValue = "") label: String,
        @RequestParam(defaultValue = "") q: String,
        @RequestParam(defaultValue = "id") sort: String,
        model: Model,
    ): String {
        val workbench = workbenches.read(token, run)
        val filter = WorkbenchFilter(optionId, label, q.take(500), sort)
        val rows = filter.sorted(workbench.rows)
        model.addAttribute("workbench", workbench)
        model.addAttribute("workbenchRows", rows)
        model.addAttribute("workbenchFilter", filter)
        model.addAttribute("visibleIds", rows.filter(filter::matches).map { it.id }.toSet())
        model.addAttribute("workbenchCountGroups", workbench.options.filter { optionId == null || it.id == optionId }
            .associate { option ->
                option.name to workbenchCounts(rows.filter(filter::matches), option.id).mapKeys { (label, _) ->
                    rows.flatMap { it.labels }.first { it.label == label }.displayLabel
                }
            })
        return "workbench/page"
    }

    @PostMapping("/w/{token}/maker/proposal/wishes/hide")
    fun hide(
        @PathVariable token: String,
        @RequestParam run: UUID,
        @RequestParam optionId: UUID,
        @RequestParam wishIds: List<UUID>,
        redirect: RedirectAttributes,
    ): String {
        workbenches.hide(token, run, optionId, wishIds)
        redirect.addFlashAttribute("workbenchNotice", "Selected wishes hidden. The stored analysis remains a snapshot.")
        return "redirect:/w/$token/maker/proposal/wishes?run=$run&optionId=$optionId"
    }

    @ExceptionHandler(WorkspaceNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(): String = "error/not-found"

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidSelection(model: Model): String {
        model.addAttribute("message", "Invalid wish selection. Return to the workbench and select wishes from this run.")
        return "workbench/invalid"
    }
}

package app.web.progress

import app.campaign.progress.AnalysisProgressService
import app.campaign.progress.AnalysisProgressView
import app.campaign.progress.ProgressStart
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
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.util.UUID

@Controller
class AnalysisProgressController(
    private val progress: AnalysisProgressService,
    private val workspaces: WorkspaceService,
) {
    @GetMapping("/w/{token}/maker/analysis-progress")
    fun page(@PathVariable token: String, @RequestParam(required = false) job: UUID?, model: Model): String {
        val current = if (job == null) progress.latest(token) else progress.find(token, job) ?: throw WorkspaceNotFoundException()
        val options = workspaces.listOptions(token)
        model.addAttribute("token", token)
        model.addAttribute("job", current)
        model.addAttribute("active", current?.status in setOf("QUEUED", "RUNNING"))
        model.addAttribute("options", options)
        model.addAttribute("finalCounts", current?.finalCounts?.associateBy { it.optionId }.orEmpty())
        return "progress/page"
    }

    @PostMapping("/w/{token}/maker/analysis-progress")
    fun start(@PathVariable token: String, redirect: RedirectAttributes): String {
        val job = when (val result = progress.start(token)) {
            is ProgressStart.Started -> result.job
            is ProgressStart.Existing -> result.job
            ProgressStart.Busy -> {
                redirect.addFlashAttribute("progressError", "Analysis is busy. Try again later; no new analysis was started.")
                null
            }
            ProgressStart.NoVisibleWishes -> {
                redirect.addFlashAttribute("progressError", "There are no visible wishes to analyze.")
                null
            }
            is ProgressStart.Failed -> {
                redirect.addFlashAttribute("progressError", "Analysis could not start. Your last valid analysis is still available.")
                null
            }
        }
        return "redirect:/w/$token/maker/analysis-progress" + (job?.let { "?job=${it.id}" } ?: "")
    }

    @GetMapping("/w/{token}/maker/analysis-progress/{jobId}")
    @ResponseBody
    fun poll(@PathVariable token: String, @PathVariable jobId: UUID): AnalysisProgressView =
        progress.find(token, jobId) ?: throw WorkspaceNotFoundException()

    @ExceptionHandler(WorkspaceNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(): String = "error/not-found"
}

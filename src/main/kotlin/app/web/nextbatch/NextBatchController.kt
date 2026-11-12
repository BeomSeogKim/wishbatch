package app.web.nextbatch

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.nextbatch.nextBatch
import app.campaign.workspace.WorkspaceNotFoundException
import app.campaign.workspace.WorkspaceService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import java.util.UUID

@Controller
class NextBatchController(private val runs: AnalysisRunService, private val workspaces: WorkspaceService) {
    @GetMapping("/w/{token}/maker/next-batch")
    fun page(@PathVariable token: String, @RequestParam(required = false) run: UUID?, model: Model): String {
        val stored = if (run == null) runs.latest(token) else runs.find(token, run) ?: throw WorkspaceNotFoundException()
        val options = workspaces.listOptions(token)
        val wishes = workspaces.listWishes(token, includeHidden = false)
        model.addAttribute("token", token)
        model.addAttribute("stored", stored)
        model.addAttribute("nextBatch", stored?.let { nextBatch(it.output, options, wishes) })
        return "nextbatch/page"
    }

    @ExceptionHandler(WorkspaceNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(): String = "error/not-found"
}

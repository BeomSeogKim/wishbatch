package app.web.drills

import app.campaign.drills.ReliabilityDrills
import app.campaign.workspace.WorkspaceNotFoundException
import app.campaign.workspace.WorkspaceService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.ResponseStatus

@Controller
class ReliabilityDrillsController(private val workspaces: WorkspaceService) {
    @GetMapping("/w/{token}/maker/reliability-drills")
    fun page(@PathVariable token: String, model: Model): String {
        workspaces.findByToken(token) ?: throw WorkspaceNotFoundException()
        model.addAttribute("token", token)
        model.addAttribute("drills", ReliabilityDrills.play())
        return "drills/page"
    }

    @ExceptionHandler(WorkspaceNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(): String = "error/not-found"
}

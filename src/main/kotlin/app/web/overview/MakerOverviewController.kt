package app.web.overview

import app.campaign.overview.MakerOverviewQuery
import app.campaign.workspace.WorkspaceNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.ResponseStatus

@Controller
class MakerOverviewController(private val overview: MakerOverviewQuery) {
    @GetMapping("/w/{token}/maker/overview")
    fun page(@PathVariable token: String, model: Model): String {
        model.addAttribute("token", token)
        model.addAttribute("overview", overview.read(token))
        return "overview/page"
    }

    @ExceptionHandler(WorkspaceNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(): String = "error/not-found"
}

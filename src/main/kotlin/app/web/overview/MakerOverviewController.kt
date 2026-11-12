package app.web.overview

import app.campaign.overview.MakerOverviewQuery
import app.campaign.overview.OverviewAction
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
        val snapshot = overview.read(token)
        model.addAttribute("overview", snapshot)
        model.addAttribute("nextActionHelp", overviewActionHelp(snapshot.action))
        return "overview/page"
    }

    @ExceptionHandler(WorkspaceNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(): String = "error/not-found"
}

internal fun overviewActionHelp(action: OverviewAction): String = when (action.label) {
    "Review payment attention" -> "Check the affected pledges and pending payment actions before proceeding with production."
    "Review wishes and analysis" -> "Review the available wishes and run analysis to prepare a proposal."
    "Review proposal" -> "Check the proposed batch, source wishes, and unresolved questions before confirming offer terms."
    "Review confirmation" -> "Review the authorized holds and exact offer terms before confirming production. Captures can still fail."
    "Review campaign" -> "Check the campaign window, authorized holds, and available actions. Holds are not completed payments."
    "Review settlement" -> "Check completed captures and any pending refunds or hold releases. Pending payments do not count as paid."
    "Review result" -> "Review the recorded outcome and each pledge's payment status before deciding what to do next."
    else -> "Open the linked page to review the supporting records and available actions."
}

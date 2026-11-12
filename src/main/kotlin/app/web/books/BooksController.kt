package app.web.books

import app.campaign.books.BooksCheckService
import app.campaign.service.CampaignRef
import app.campaign.service.ServiceResult
import app.campaign.workspace.WorkspaceNotFoundException
import app.campaign.workspace.WorkspaceService
import app.web.maker.MakerCampaignLookup
import org.springframework.core.io.ClassPathResource
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
import java.util.UUID

@Controller
class BooksController(
    private val books: BooksCheckService,
    private val workspaces: WorkspaceService,
    private val lookup: MakerCampaignLookup,
) {
    @GetMapping("/w/{token}/maker/books")
    fun ledger(
        @PathVariable token: String,
        @RequestParam(required = false) campaignId: UUID?,
        @RequestParam(required = false) checkId: UUID?,
        model: Model,
    ): String {
        val ref = ref(token, campaignId)
        val history = (books.history(ref) as? ServiceResult.Success)?.value ?: throw WorkspaceNotFoundException()
        val selected = if (checkId == null) history.lastOrNull() else history.find { it.id == checkId }
            ?: throw WorkspaceNotFoundException()
        model.addAttribute("campaignId", ref.campaignId)
        model.addAttribute("history", history.asReversed())
        model.addAttribute("check", selected)
        model.addAttribute("gridAvailable", ClassPathResource("templates/fragments/data-tools.html").exists())
        return "books/ledger"
    }

    @PostMapping("/w/{token}/maker/books")
    fun check(@PathVariable token: String, @RequestParam campaignId: UUID): String {
        val result = (books.check(ref(token, campaignId)) as? ServiceResult.Success)?.value
            ?: throw WorkspaceNotFoundException()
        return "redirect:/w/$token/maker/books?campaignId=$campaignId&checkId=${result.id}"
    }

    @ResponseBody
    @GetMapping("/js/books-ledger.js", produces = ["text/javascript"])
    fun script(): ClassPathResource = ClassPathResource("templates/books/ledger.js")

    private fun ref(token: String, campaignId: UUID?): CampaignRef {
        val workspace = workspaces.findByToken(token) ?: throw WorkspaceNotFoundException()
        return campaignId?.let { CampaignRef(workspace.id, it) } ?: lookup.current(workspace.id)
            ?: throw WorkspaceNotFoundException()
    }

    @ExceptionHandler(WorkspaceNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(): String = "error/not-found"
}

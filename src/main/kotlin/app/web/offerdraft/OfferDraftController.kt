package app.web.offerdraft

import app.campaign.offerdraft.InvalidOfferDraftException
import app.campaign.offerdraft.OfferDraftGeneration
import app.campaign.offerdraft.OfferDraftService
import app.campaign.workspace.WorkspaceNotFoundException
import org.springframework.http.HttpStatus
import app.web.errors.helperToken
import jakarta.servlet.http.HttpServletRequest
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
class OfferDraftController(private val drafts: OfferDraftService) {
    @GetMapping("/w/{token}/maker/offer-draft")
    fun review(@PathVariable token: String, @RequestParam run: UUID, model: Model): String {
        model.addAttribute("token", token)
        model.addAttribute("page", drafts.page(token, run))
        return "offerdraft/review"
    }

    @PostMapping("/w/{token}/maker/offer-draft/generate")
    fun generate(
        @PathVariable token: String,
        @RequestParam run: UUID,
        @RequestParam sourceHash: String,
        redirect: RedirectAttributes,
    ): String {
        if (drafts.generate(token, run, sourceHash) is OfferDraftGeneration.Unavailable) {
            redirect.addFlashAttribute("draftNotice", "Draft unavailable. Existing reviewed text is unchanged.")
        }
        return "redirect:/w/$token/maker/offer-draft?run=$run"
    }

    @PostMapping("/w/{token}/maker/offer-draft/save")
    fun save(
        @PathVariable token: String,
        @RequestParam run: UUID,
        @RequestParam draft: UUID,
        @RequestParam sourceHash: String,
        @RequestParam title: String,
        @RequestParam description: String,
        redirect: RedirectAttributes,
    ): String {
        drafts.save(token, run, draft, sourceHash, title, description)
        redirect.addFlashAttribute("draftNotice", "Reviewed draft saved. Publication still requires offer confirmation.")
        return "redirect:/w/$token/maker/offer-draft?run=$run"
    }

    @ExceptionHandler(WorkspaceNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(): String = "error/not-found"

    @ExceptionHandler(InvalidOfferDraftException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalid(request: HttpServletRequest, model: Model): String {
        model.addAttribute("token", helperToken(request))
        return "offerdraft/unavailable"
    }
}

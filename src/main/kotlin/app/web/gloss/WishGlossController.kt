package app.web.gloss

import app.campaign.gloss.GlossResult
import app.campaign.gloss.GlossStatus
import app.campaign.gloss.WishGlossService
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
class WishGlossController(private val glosses: WishGlossService) {
    @GetMapping("/w/{token}/maker/glosses")
    fun page(@PathVariable token: String, @RequestParam run: UUID, model: Model): String {
        model.addAttribute("token", token)
        val page = glosses.read(token, run)
        model.addAttribute("page", page)
        model.addAttribute("translated", page.rows.filter { it.current && it.saved?.status == GlossStatus.TRANSLATED })
        model.addAttribute("otherResults", page.rows.filter { it.current && it.saved?.status != null && it.saved.status != GlossStatus.TRANSLATED })
        model.addAttribute("unchecked", page.rows.filter { it.saved?.status == null })
        return "gloss/page"
    }

    @PostMapping("/w/{token}/maker/glosses")
    fun generate(
        @PathVariable token: String,
        @RequestParam run: UUID,
        @RequestParam(required = false, defaultValue = "") wishIds: List<UUID>,
        redirect: RedirectAttributes,
    ): String {
        val result = glosses.generate(token, run, wishIds)
        redirect.addFlashAttribute("glossNotice", if (result is GlossResult.Completed) {
            "Reading aids saved. Review machine translations beside the original wishes."
        } else {
            "Translation is unavailable. Original wishes are unchanged."
        })
        return "redirect:/w/$token/maker/glosses?run=$run"
    }

    @ExceptionHandler(WorkspaceNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(): String = "error/not-found"

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalid(request: HttpServletRequest, model: Model): String {
        model.addAttribute("token", helperToken(request))
        return "gloss/invalid"
    }
}

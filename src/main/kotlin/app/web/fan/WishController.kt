package app.web.fan

import app.campaign.workspace.SeedCatalog
import app.campaign.workspace.WishLimitReachedException
import app.campaign.workspace.WorkspaceService
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

fun interface WishCampaignAvailability {
    fun isOpen(token: String): Boolean
}

data class WishForm(val text: String = "", val displayName: String = "")

@Controller
class WishController(
    private val workspaces: WorkspaceService,
    private val catalog: SeedCatalog,
    private val campaigns: WishCampaignAvailability? = null,
) {
    @GetMapping("/w/{token}")
    fun wish(@PathVariable token: String, model: Model, response: HttpServletResponse): String {
        if (workspaces.findByToken(token) == null) return notFound(model, response)
        model.addAttribute("workspaceFound", true)
        model.addAttribute("token", token)
        model.addAttribute("maker", catalog.definition.maker)
        model.addAttribute("recentWishes", workspaces.listWishes(token, includeHidden = false).asReversed().take(5))
        model.addAttribute("campaignOpen", campaigns?.isOpen(token) ?: false)
        if (!model.containsAttribute("wishForm")) model.addAttribute("wishForm", WishForm())
        return "fan/wish"
    }

    @PostMapping("/w/{token}")
    fun submit(
        @PathVariable token: String,
        @RequestParam(defaultValue = "") text: String,
        @RequestParam(defaultValue = "") displayName: String,
        redirect: RedirectAttributes,
        model: Model,
        response: HttpServletResponse,
    ): String {
        if (workspaces.findByToken(token) == null) return notFound(model, response)
        try {
            workspaces.addWish(token, text, displayName)
            redirect.addFlashAttribute("wishSubmitted", true)
        } catch (_: WishLimitReachedException) {
            redirect.addFlashAttribute("formError", "This workspace has reached its 200-wish limit. Thanks for your interest.")
            redirect.addFlashAttribute("wishForm", WishForm(text, displayName))
        } catch (_: IllegalArgumentException) {
            when {
                text.isBlank() -> redirect.addFlashAttribute("textError", "Write a little about what you would like.")
                text.trim().length > 500 -> redirect.addFlashAttribute("textError", "Keep your wish to 500 characters or fewer.")
                else -> redirect.addFlashAttribute("nameError", "Keep your display name to 255 characters or fewer.")
            }
            redirect.addFlashAttribute("wishForm", WishForm(text, displayName))
        }
        return "redirect:/w/$token"
    }

    private fun notFound(model: Model, response: HttpServletResponse): String {
        response.status = HttpServletResponse.SC_NOT_FOUND
        model.addAttribute("workspaceFound", false)
        return "fan/wish"
    }
}

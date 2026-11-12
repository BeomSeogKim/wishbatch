package app.web.fan

import app.campaign.model.CompatibilityVerdict
import app.campaign.model.NeedStrength
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.port.AnalysisResult
import app.campaign.port.WishAnalyzer
import app.campaign.workspace.SeedCatalog
import app.campaign.workspace.WishLimitReachedException
import app.campaign.workspace.WorkspaceService
import app.campaign.service.CampaignQueries
import app.campaign.service.ServiceResult
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Controller
import org.springframework.stereotype.Component
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

fun interface WishCampaignAvailability {
    fun isOpen(token: String): Boolean

    fun openOption(token: String): Option? = null
}

@Component
class OpenWishCampaign(
    private val campaigns: CampaignQueries,
    private val workspaces: WorkspaceService,
) : WishCampaignAvailability {
    override fun isOpen(token: String): Boolean = campaigns.isOpenForPledges(token)

    override fun openOption(token: String): Option? {
        if (!isOpen(token)) return null
        val campaign = (campaigns.currentCampaign(token) as? ServiceResult.Success)?.value ?: return null
        if (!isOpen(token)) return null
        return workspaces.listOptions(token).singleOrNull { it.id == campaign.offer.optionId }
    }
}

data class WishForm(val text: String = "", val displayName: String = "")
data class WishMatchNote(val message: String, val quote: String, val showOffer: Boolean)

@Controller
class WishController(
    private val workspaces: WorkspaceService,
    private val catalog: SeedCatalog,
    private val analyzer: WishAnalyzer,
    private val campaigns: WishCampaignAvailability? = null,
) {
    @GetMapping("/w/{token}")
    fun wish(@PathVariable token: String, model: Model, response: HttpServletResponse): String {
        if (workspaces.findByToken(token) == null) return notFound(response)
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
        response: HttpServletResponse,
    ): String {
        if (workspaces.findByToken(token) == null) return notFound(response)
        val saved = try {
            val wish = workspaces.addWish(token, text, displayName)
            redirect.addFlashAttribute("wishSubmitted", true)
            wish
        } catch (_: WishLimitReachedException) {
            redirect.addFlashAttribute("formError", "This workspace has reached its 200-wish limit. Thanks for your interest.")
            redirect.addFlashAttribute("wishForm", WishForm(text, displayName))
            null
        } catch (_: IllegalArgumentException) {
            when {
                text.isBlank() -> redirect.addFlashAttribute("textError", "Write a little about what you would like.")
                text.trim().length > 500 -> redirect.addFlashAttribute("textError", "Keep your wish to 500 characters or fewer.")
                else -> redirect.addFlashAttribute("nameError", "Keep your display name to 255 characters or fewer.")
            }
            redirect.addFlashAttribute("wishForm", WishForm(text, displayName))
            null
        }
        if (saved != null) {
            val note = runCatching { matchNote(token, saved) }.getOrNull()
            if (note != null) redirect.addFlashAttribute("wishMatchNote", note)
        }
        return "redirect:/w/$token"
    }

    private fun matchNote(token: String, wish: Wish): WishMatchNote? {
        val option = campaigns?.openOption(token) ?: return null
        val result = analyzer.classify(option, wish) as? AnalysisResult.Success ?: return null
        val output = result.validated.output
        val analyzed = output.wishes.singleOrNull { it.wishId == wish.id && it.kind == WishKind.REQUEST } ?: return null
        val verdict = output.verdicts.singleOrNull { it.wishId == wish.id && it.optionId == option.id } ?: return null
        val needs = analyzed.needs.filter { it.quote.isNotBlank() && wish.text.contains(it.quote) }
        if (needs.isEmpty()) return null
        val evidence = needs.filter { it.strength != NeedStrength.PREFERENCE }.ifEmpty { needs }
        val quote = wish.text.substring(
            evidence.minOf { wish.text.indexOf(it.quote) },
            evidence.maxOf { wish.text.indexOf(it.quote) + it.quote.length },
        )
        return when (verdict.verdict) {
            CompatibilityVerdict.COMPATIBLE -> WishMatchNote("This matches the open offer", quote, true)
            CompatibilityVerdict.CONDITIONAL -> WishMatchNote(
                "This may match, depending on whether the offer meets your condition:", quote, true)
            CompatibilityVerdict.INCOMPATIBLE -> WishMatchNote(
                "The open offer is not what you asked for; your wish is saved for a future batch", quote, false)
        }
    }

    private fun notFound(response: HttpServletResponse): String {
        response.status = HttpServletResponse.SC_NOT_FOUND
        return "error/not-found"
    }
}

package app.web.fan

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.reading.OfferWishReadingService
import app.campaign.replies.ReplyService
import app.campaign.workspace.WishLimitReachedException
import app.campaign.workspace.WorkspaceService
import app.campaign.service.CampaignQueries
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Controller
import org.springframework.stereotype.Component
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.util.UUID

fun interface WishCampaignAvailability {
    fun isOpen(token: String): Boolean
}

@Component
class OpenWishCampaign(
    private val campaigns: CampaignQueries,
) : WishCampaignAvailability {
    override fun isOpen(token: String): Boolean = campaigns.isOpenForPledges(token)
}

data class WishForm(val text: String = "", val displayName: String = "")
data class WishMatchNote(val message: String, val quote: String, val showOffer: Boolean)

@Controller
class WishController(
    private val workspaces: WorkspaceService,
    private val readings: OfferWishReadingService,
    private val campaigns: WishCampaignAvailability? = null,
    private val replies: ReplyService,
    private val analyses: AnalysisRunService,
) {
    @GetMapping("/w/{token}")
    fun wish(@PathVariable token: String, model: Model, response: HttpServletResponse, session: HttpSession): String {
        if (workspaces.findByToken(token) == null) return notFound(response)
        model.addAttribute("token", token)
        model.addAttribute("maker", workspaces.scenario(token).definition.maker)
        val nonRequests = analyses.latest(token)?.output?.wishes.orEmpty()
            .filter { it.kind == WishKind.NOT_A_REQUEST }.map { it.wishId }.toSet()
        model.addAttribute("recentWishes", workspaces.listWishes(token, includeHidden = false)
            .asReversed().filterNot { it.id in nonRequests }.take(5))
        model.addAttribute("campaignOpen", campaigns?.isOpen(token) ?: false)
        val lastWishId = session.getAttribute("followup.wish.$token") as? UUID
        model.addAttribute("lastWishId", lastWishId)
        model.addAttribute("approvedReply", lastWishId?.let { replies.latestApprovedForWish(token, it) })
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
        session: HttpSession,
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
            session.setAttribute("followup.wish.$token", saved.id)
            redirect.addFlashAttribute("submittedWishId", saved.id)
            val note = runCatching { matchNote(token, saved) }.getOrNull()
            if (note != null) redirect.addFlashAttribute("wishMatchNote", note)
        }
        return "redirect:/w/$token"
    }

    private fun matchNote(token: String, wish: Wish): WishMatchNote? {
        val reading = readings.assessOpen(token, wish.id) ?: return null
        if (reading.path("availability").asString() != "AVAILABLE" || reading.path("kind").asString() != "REQUEST") return null
        val quote = reading.get("quote")?.takeUnless { it.isNull }?.asString() ?: return null
        return when (reading.path("verdict").asString()) {
            "COMPATIBLE" -> WishMatchNote("This matches the product format. Check the price, finish, and delivery terms in the offer.", quote, true)
            "CONDITIONAL" -> WishMatchNote(
                "This may match, depending on whether the offer meets your condition:", quote, true)
            "INCOMPATIBLE" -> WishMatchNote(
                "The open offer is not what you asked for; your wish is saved for a future batch", quote, false)
            else -> null
        }
    }

    private fun notFound(response: HttpServletResponse): String {
        response.status = HttpServletResponse.SC_NOT_FOUND
        return "error/not-found"
    }
}

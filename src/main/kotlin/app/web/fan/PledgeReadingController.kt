package app.web.fan

import app.campaign.reading.OfferWishReadingService
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping

@Controller
class PledgeReadingController(private val readings: OfferWishReadingService) {
    @PostMapping("/w/{token}/pledges/{id}/trace/reading")
    fun retry(@PathVariable token: String, @PathVariable id: String, response: HttpServletResponse): String {
        val pledgeId = parsePledgeId(id) ?: return fanNotFound(response)
        if (!readings.retry(token, pledgeId)) return fanNotFound(response)
        return "redirect:/w/$token/pledges/$id/trace"
    }
}

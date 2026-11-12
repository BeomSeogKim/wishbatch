package app.web.charts

import app.campaign.charts.CampaignChartsQuery
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Controller
class CampaignChartsController(private val charts: CampaignChartsQuery) {
    @GetMapping("/w/{token}/maker/campaign/{campaignId}/charts")
    fun page(@PathVariable token: String, @PathVariable campaignId: UUID, model: Model): String {
        model.addAttribute("charts", charts.read(token, campaignId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND))
        model.addAttribute("token", token)
        return "charts/campaign"
    }
}

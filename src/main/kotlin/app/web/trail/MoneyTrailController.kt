package app.web.trail

import app.campaign.trail.MoneyTrail
import app.campaign.trail.TrailEntry
import app.campaign.trail.TrailComparison
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import java.util.UUID

fun csvCell(value: String): String {
    val safe = if (value.firstOrNull() in listOf('\t', '\r', '\n') || value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
    return "\"${safe.replace("\"", "\"\"")}\""
}

fun trailCsv(entries: List<TrailEntry>): String =
    (listOf(listOf("Pledge ID", "Checkpoint time (UTC)", "Status", "Provider ID", "Amount")) +
        entries.map { listOf(it.pledgeId.toString(), it.checkpoint.toString(), it.status, it.providerId.orEmpty(), it.amount) })
        .joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",") { csvCell(it) } }

fun comparisonCsv(rows: List<TrailComparison>): String =
    (listOf(listOf("Pledge ID", "Current local state", "Pledge amount", "Checked at (UTC)", "Local state at check", "Provider resources at check", "Match at check")) +
        rows.map { listOf(it.pledgeId.toString(), it.currentState, it.amount, it.checkedAt?.toString().orEmpty(), it.checkedState, it.providerStates, it.match) })
        .joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",") { csvCell(it) } }

@Controller
class MoneyTrailController(private val trail: MoneyTrail) {
    @GetMapping("/w/{token}/maker/campaigns/{campaignId}/trail")
    fun page(@PathVariable token: String, @PathVariable campaignId: UUID, model: Model): String {
        model.addAttribute("entries", entries(token, campaignId))
        model.addAttribute("comparisons", comparisons(token, campaignId))
        model.addAttribute("booksChecks", trail.booksChecks(token, campaignId).orEmpty())
        model.addAttribute("token", token)
        model.addAttribute("campaignId", campaignId)
        return "trail/ledger"
    }

    @GetMapping("/w/{token}/maker/campaigns/{campaignId}/trail.csv")
    fun csv(@PathVariable token: String, @PathVariable campaignId: UUID): ResponseEntity<String> =
        ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=money-trail.csv")
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .body(trailCsv(entries(token, campaignId)))

    @GetMapping("/w/{token}/maker/campaigns/{campaignId}/comparison.csv")
    fun comparisonCsv(@PathVariable token: String, @PathVariable campaignId: UUID): ResponseEntity<String> =
        ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=payment-comparison.csv")
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .body(comparisonCsv(comparisons(token, campaignId)))

    private fun comparisons(token: String, campaignId: UUID) = trail.comparison(token, campaignId)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    private fun entries(token: String, campaignId: UUID) = trail.campaign(token, campaignId)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
}

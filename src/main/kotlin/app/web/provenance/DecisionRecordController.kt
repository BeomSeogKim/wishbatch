package app.web.provenance

import app.campaign.provenance.DecisionRecordDocument
import app.campaign.provenance.DecisionRecordQuery
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException

@Controller
class DecisionRecordController(private val records: DecisionRecordQuery) {
    @GetMapping("/w/{token}/maker/decision-record")
    fun page(@PathVariable token: String, model: Model): String {
        model.addAttribute("record", read(token))
        model.addAttribute("token", token)
        return "provenance/decision-record"
    }

    @GetMapping("/w/{token}/maker/decision-record.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun download(@PathVariable token: String): ResponseEntity<DecisionRecordDocument> = ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"decision-record.json\"")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(read(token))

    private fun read(token: String): DecisionRecordDocument = records.findByToken(token)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
}

package app.web.fan

import app.campaign.provenance.DecisionRecordQuery
import app.campaign.provenance.PledgeDecisionView
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import tools.jackson.databind.JsonNode
import java.time.Instant

data class TraceReference(val label: String, val value: String)
data class TracePayment(val label: String, val recordedAt: Instant?, val references: List<TraceReference>, val hash: String)
data class TraceTerms(
    val title: String?, val specification: String?, val price: Long?, val currency: String?,
    val delivery: String?, val region: String?, val minimum: String?, val capacity: String?,
    val variants: List<TraceReference>,
)
data class PledgeTraceView(
    val wish: String?, val wishHash: String?, val wishExplanation: String,
    val verdict: String?, val readingReason: String?, val readingAgreement: String,
    val reviewVerdict: String?, val reviewReason: String?, val reviewSource: String?, val reviewRevision: String?,
    val model: String?, val runId: String?, val inputHash: String?,
    val offerId: String?, val termsHash: String?, val terms: TraceTerms?,
    val payments: List<TracePayment>, val evidenceCount: Int?,
    val quantity: Int?, val amount: Long?, val currency: String?,
)

internal fun pledgeTrace(record: PledgeDecisionView): PledgeTraceView {
    val wish = record.entries.firstOrNull { it.kind == "WISH" }?.payload
    val verdict = record.entries.firstOrNull { it.kind == "VERDICT" }?.payload
    val offer = record.entries.firstOrNull { it.kind == "OFFER" }?.payload
    val terms = offer?.get("terms")?.takeUnless { it.isNull }
    val comparison = verdict?.get("readingAgreement")?.takeUnless { it.isNull }
    val review = verdict?.get("review")?.takeUnless { it.isNull }
    val payment = record.entries.firstOrNull { it.kind == "PAYMENT" }?.payload
    return PledgeTraceView(
        wish.text("text"), wish.text("textHash"),
        if (wish.text("traceScope") == "OFFER_EVIDENCE")
            "No wish is linked to this pledge. It traces to the offer's recorded evidence set."
        else wish.text("reason") ?: "Earlier wish evidence is unavailable; no association is inferred.",
        verdict.text("originalVerdict") ?: verdict.text("verdict") ?: verdict.text("kind")?.takeIf { it == "NOT_A_REQUEST" },
        verdict.text("originalReason") ?: verdict.text("reason"),
        when {
            comparison == null -> "A second reading was not recorded for this wish and option."
            comparison.get("needsYourEyes")?.asBoolean() == true -> "The two readings differed. Maker review is needed."
            else -> "The two readings agreed on this wish and option."
        },
        review.text("verdict"), review.text("reason"), review.text("source"), review.text("revision"),
        verdict.text("model"), verdict.text("analysisRunId"), verdict.text("inputHash"),
        offer.text("offerId"), offer.text("termsHash"),
        terms?.let {
            TraceTerms(it.text("title"), it.text("specText"), it.get("unitPriceMinor")?.asLong(), it.text("currency"),
                it.text("deliveryTerms"), it.text("shipRegion"), it.text("minBatch"), it.text("capacity"),
                it.get("chosenVariants")?.properties()?.map { field -> TraceReference(field.key, field.value.asString()) }.orEmpty())
        },
        record.entries.filter { it.kind == "PAYMENT" }.map { entry ->
            val payment = entry.payload
            TracePayment(paymentLabel(payment.text("state"), payment.text("orderId")),
                payment.text("recordedAt")?.let { runCatching { Instant.parse(it) }.getOrNull() },
                listOf("orderId" to "PayPal order", "authorizationId" to "Authorization", "captureId" to "Capture", "refundId" to "Refund")
                    .mapNotNull { (key, label) -> payment.text(key)?.let { TraceReference(label, it) } }, entry.hash)
        }, wish?.get("supportingWishIds")?.takeIf { it.isArray }?.size(),
        payment?.get("quantity")?.asInt(), payment?.get("amountMinor")?.asLong(), payment.text("currency"),
    )
}

private fun JsonNode?.text(key: String): String? = this?.get(key)?.takeUnless { it.isNull }?.asString()

private fun paymentLabel(state: String?, orderId: String?): String = when (state) {
    "CREATED" -> if (orderId == null) "Pledge started" else "PayPal order created"
    "AUTHORIZED" -> "Authorized hold"
    "CAPTURED" -> "Paid"
    "CAPTURE_PENDING" -> "Payment pending"
    "CAPTURE_FAILED" -> "Payment could not be completed"
    "VOIDED" -> "Released"
    "VOID_FAILED" -> "Release not confirmed"
    "REFUNDED" -> "Refund completed"
    "REFUND_PENDING" -> "Refund requested"
    "REFUND_FAILED" -> "Refund not confirmed"
    "PARTIALLY_REFUNDED" -> "Partial refund observed"
    "ABANDONED" -> "Approval not completed"
    else -> "Payment status unavailable"
}

@Controller
class PledgeTraceController(private val records: DecisionRecordQuery) {
    @GetMapping("/w/{token}/pledges/{id}/trace")
    fun trace(@PathVariable token: String, @PathVariable id: String, model: Model, response: HttpServletResponse): String {
        val pledgeId = parsePledgeId(id) ?: return fanNotFound(response)
        val record = records.findPledgeByToken(token, pledgeId) ?: return fanNotFound(response)
        response.setHeader("Cache-Control", "no-store")
        model.addAttribute("token", token)
        model.addAttribute("record", record)
        model.addAttribute("trace", pledgeTrace(record))
        return "fan/trace"
    }
}

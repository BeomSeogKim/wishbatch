package app.web.provenance

import app.web.layout.HumanDisplay
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

@Component("termsCheckDisplay")
class TermsCheckDisplay(private val mapper: JsonMapper, private val human: HumanDisplay) {
    fun fromJson(raw: String?): String = try {
        if (raw == null) "Terms check unavailable" else summary(mapper.readTree(raw))
    } catch (_: Exception) {
        "Terms check unavailable"
    }

    fun summary(record: JsonNode): String {
        val outcome = record.path("outcome")
        val label = when (if (outcome.isString) outcome.asString() else "") {
            "MATCH" -> "Terms matched PayPal"
            "MISMATCH" -> "Terms did not match"
            else -> "Terms check unavailable"
        }
        val amount = record.path("expectedAmountMinor")
        val currency = record.path("expectedCurrency")
        val quantity = record.path("quantity")
        return buildList {
            add(label)
            if (amount.isIntegralNumber && amount.canConvertToLong() && currency.isString) {
                val formatted = runCatching { human.money(amount.asLong(), currency.asString()) }.getOrNull()
                if (formatted != null) add("Expected $formatted")
            }
            if (quantity.isIntegralNumber && quantity.canConvertToInt() && quantity.asInt() > 0) {
                val units = quantity.asInt()
                add("$units ${if (units == 1) "unit" else "units"}")
            }
        }.joinToString(" · ")
    }
}

package app.web.layout

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

@Component("humanDisplay")
class HumanDisplay {
    fun time(value: Instant?): String = value?.let { timeFormat.format(it) } ?: "Not available"

    fun money(amount: Long, currency: String): String = money(BigInteger.valueOf(amount), currency)

    fun money(amount: BigInteger, currency: String): String =
        "$currency ${BigDecimal(amount, Currency.getInstance(currency).defaultFractionDigits).toPlainString()}"

    // Trail snapshots retain their original minor-unit text for machine exports.
    fun trailMoney(value: String): String {
        val match = trailAmount.matchEntire(value) ?: return value
        return money(match.groupValues[1].toBigInteger(), match.groupValues[2])
    }

    fun state(value: Any?): String {
        val text = (value as? Enum<*>)?.name ?: value?.toString() ?: return "Unknown"
        return labels[text] ?: text.split(" / ", " -> ").let { parts ->
            if (parts.size > 1) parts.joinToString(if (" -> " in text) " → " else " / ") { state(it) }
            else text.lowercase(Locale.US).replace('_', ' ').replaceFirstChar { it.titlecase(Locale.US) }
        }
    }

    fun shortId(value: Any?): String = value?.toString()?.let {
        if (it.length > 12) it.take(8) + "…" else it
    } ?: "Unknown"

    private companion object {
        val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm 'UTC'", Locale.US)
            .withZone(ZoneOffset.UTC)
        val trailAmount = Regex("(-?\\d+) ([A-Z]{3}) minor units")
        val labels = mapOf(
            "DRAFT" to "Draft", "OPEN" to "Open", "AWAITING_CONFIRMATION" to "Awaiting maker confirmation",
            "SETTLING" to "Settlement in progress", "FUNDED" to "Funded",
            "NOT_FUNDED" to "Not funded", "THRESHOLD_NOT_MET" to "Minimum not met",
            "CANCELLED_BY_MAKER" to "Cancelled by maker", "CONFIRMATION_EXPIRED" to "Confirmation expired",
            "CAPTURE_SHORTFALL" to "Completed payments below minimum", "CREATED" to "Created",
            "AUTHORIZED" to "Hold placed", "ABANDONED" to "Approval abandoned", "CAPTURED" to "Paid",
            "CAPTURE_PENDING" to "Payment pending", "CAPTURE_FAILED" to "Payment failed",
            "VOIDED" to "Hold released", "VOID_FAILED" to "Hold release unconfirmed",
            "REFUNDED" to "Refunded", "REFUND_PENDING" to "Refund pending", "REFUND_FAILED" to "Refund failed",
            "PARTIALLY_REFUNDED" to "Partially refunded", "UNKNOWN" to "Unknown", "INTACT" to "Record intact",
            "MATCH" to "Match", "MISMATCH" to "Mismatch", "COMPLETED" to "Completed",
            "COMPATIBLE" to "Compatible", "CONDITIONAL" to "Depends", "INCOMPATIBLE" to "Not served",
            "NOT_A_REQUEST" to "Not a request",
        )
    }
}

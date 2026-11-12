package app.campaign.demand

import app.campaign.model.NeedAttribute
import app.campaign.model.NeedStrength
import app.campaign.port.AnalyzedWish
import java.math.BigDecimal

enum class PriceEvidenceStatus { QUOTED_LIMIT, EXPLICITLY_UNBOUNDED, MISSING, AMBIGUOUS }

data class QuotedPriceLimit(val status: PriceEvidenceStatus, val maximumMinor: Long?, val quotes: List<String>)

/** Only explicit USD unit caps are supported; totals, shipping and other currencies stay unknown. */
fun quotedPriceLimit(wish: AnalyzedWish, source: String, currency: String): QuotedPriceLimit {
    val needs = wish.needs.filter { it.attribute == NeedAttribute.PRICE }
    if (needs.isEmpty()) return QuotedPriceLimit(PriceEvidenceStatus.MISSING, null, emptyList())
    val grounded = needs.filter { it.quote.isNotBlank() && source.contains(it.quote) }
    val quotes = grounded.map { it.quote }.distinct()
    val unknown = QuotedPriceLimit(PriceEvidenceStatus.AMBIGUOUS, null, quotes)
    if (currency != "USD" || grounded.size != needs.size || needs.any { it.strength != NeedStrength.HARD }) return unknown
    // A clipped quote can omit shipping, a foreign currency, or a multi-item condition.
    val clauses = normalizePriceQuote(source).split(Regex("(?<=[.!?]) +|; *"))
        .map { it.trim().trimEnd('.', '!', '?') }.toSet()
    if (quotes.any { normalizePriceQuote(it).trimEnd('.', '!', '?') !in clauses }) return unknown
    val parsed = quotes.map { parseUsdCap(normalizePriceQuote(it)) }
    if (parsed.all { it != null }) return QuotedPriceLimit(PriceEvidenceStatus.QUOTED_LIMIT, parsed.filterNotNull().min(), quotes)
    if (quotes.all { Regex("(?i)(?:no (?:price|budget) limit|any price|no maximum budget)[.!]?").matches(normalizePriceQuote(it)) }) {
        return QuotedPriceLimit(PriceEvidenceStatus.EXPLICITLY_UNBOUNDED, null, quotes)
    }
    return unknown
}

private fun normalizePriceQuote(text: String): String = text.replace(Regex("[\\s\\p{Z}]+"), " ").trim()

private fun parseUsdCap(quote: String): Long? {
    val money = "(?:USD ?|US\\$ ?|\\$ ?)([0-9]+(?:\\.[0-9]{1,2})?)"
    val prefix = "(?:(?:only if|only|if) )?(?:(?:I can (?:pay|spend)|my budget is|budget:?|the (?:whole )?set costs?) )?"
    val suffix = "(?: per (?:unit|set|item)| each)?[.!]?"
    val leading = Regex("$prefix(up to|at most|no more than|less than|under|below|maximum(?: of)?) $money$suffix", RegexOption.IGNORE_CASE)
    val trailing = Regex("(?:only )?(?:at )?$money (or less|max(?:imum)?)$suffix", RegexOption.IGNORE_CASE)
    val first = leading.matchEntire(quote)
    val last = trailing.matchEntire(quote)
    val amount = first?.groupValues?.get(2) ?: last?.groupValues?.get(1) ?: return null
    val minor = runCatching { BigDecimal(amount).movePointRight(2).longValueExact() }.getOrNull() ?: return null
    if (minor <= 0) return null
    return if (first?.groupValues?.get(1)?.lowercase() in setOf("less than", "under", "below")) minor - 1 else minor
}

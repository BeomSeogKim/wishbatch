package app.campaign.offerdraft

import java.text.Normalizer
import java.util.Currency
import java.util.Locale
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

data class OfferDraftRemoval(val field: String, val text: String, val reason: String)

data class OfferDraftValidation(
    val title: String?,
    val description: String?,
    val removals: List<OfferDraftRemoval>,
    val rejection: String? = null,
) {
    val available: Boolean get() = title != null && description != null
}

class OfferDraftValidator(private val mapper: JsonMapper) {
    fun schema(): Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "required" to listOf("title", "description"),
        "properties" to mapOf(
            "title" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 120),
            "description" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 1000),
        ),
    )

    fun validate(json: String, allowedSourceText: List<String>): OfferDraftValidation {
        if (json.length > 16000) return unavailable("Invalid draft schema.")
        val root = try {
            mapper.reader().with(
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
            ).readTree(json)
        } catch (_: JacksonException) {
            return unavailable("Invalid draft schema.")
        }
        if (root == null || !root.isObject || root.properties().map { it.key }.toSet() != setOf("title", "description") ||
            root["title"]?.isString != true || root["description"]?.isString != true) {
            return unavailable("Invalid draft schema.")
        }
        val title = root["title"].asString()
        val description = root["description"].asString()
        val overlong = listOf("title" to title, "description" to description).mapNotNull { (field, text) ->
            if (text.length > if (field == "title") 120 else 1000) {
                OfferDraftRemoval(field, text, "LENGTH_LIMIT")
            } else null
        }
        if (overlong.isNotEmpty()) return unavailable("Draft exceeds text limits.", overlong)

        val sources = allowedSourceText.map(::words).filter(String::isNotBlank).toSet()
        val removals = mutableListOf<OfferDraftRemoval>()
        fun sanitize(field: String, text: String): String? {
            val safe = SENTENCES.findAll(text).map { it.value.trim() }.filter(String::isNotEmpty).filter { sentence ->
                val normalized = Normalizer.normalize(sentence, Normalizer.Form.NFKC)
                val reason = when {
                    NUMBERS.containsMatchIn(sentence) || NUMBERS.containsMatchIn(normalized) ||
                        NUMBER_WORDS.containsMatchIn(normalized) -> "NUMBER"
                    CURRENCY.containsMatchIn(normalized) || COMMERCIAL.containsMatchIn(normalized) -> "COMMERCIAL_TERMS"
                    DELIVERY.containsMatchIn(normalized) -> "DELIVERY_OR_DEADLINE"
                    normalized.codePoints().anyMatch { Character.getType(it) == Character.FORMAT.toInt() ||
                        (Character.isISOControl(it) && !Character.isWhitespace(it)) } -> "UNSUPPORTED_FORMATTING"
                    words(sentence).split(" ").none { it.length > 1 && it !in CONNECTIVES } -> "NO_MEANINGFUL_TEXT"
                    // Matching a whole approved fragment prevents dropping a source's negation.
                    words(sentence) !in sources -> "UNSUPPORTED_CLAIM"
                    else -> null
                }
                if (reason != null) removals += OfferDraftRemoval(field, sentence, reason)
                reason == null
            }.toList()
            return safe.joinToString(" ").takeIf(String::isNotBlank)
        }
        val safeTitle = sanitize("title", title)
        val safeDescription = sanitize("description", description)
        if (safeTitle == null || safeDescription == null) {
            return unavailable("No meaningful safe title and description remain.", removals)
        }
        return OfferDraftValidation(safeTitle, safeDescription, removals)
    }

    private fun unavailable(reason: String, removals: List<OfferDraftRemoval> = emptyList()) =
        OfferDraftValidation(null, null, removals, reason)

    private fun words(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{M}]+"), " ").trim()

    private companion object {
        val CONNECTIVES = setOf("and", "or", "the", "of", "to", "for", "with", "in", "on", "at", "by", "from", "this", "that", "is", "are", "it", "an")
        val SENTENCES = Regex("[^.!?。！？\\r\\n]+[.!?。！？]*|[.!?。！？]+")
        val NUMBERS = Regex("\\p{N}")
        val NUMBER_WORDS = terms(
            "zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|" +
                "sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|" +
                "hundreds?|thousands?|millions?|billions?|trillions?|first|second|third|fourth|fifth|sixth|seventh|eighth|" +
                "ninth|tenth|eleventh|twelfth|single|double|triple|pairs?|dozens?|hal(?:f|ves)|quarters?|once|twice|both|" +
                "couple|several|few|many|multiple|numerous",
        )
        val CURRENCY = Regex(
            "\\p{Sc}|(?i)(?<![\\p{L}\\p{N}])(?:" +
                Currency.getAvailableCurrencies().joinToString("|") {
                    // The product noun cup collides with the uppercase currency code CUP.
                    if (it.currencyCode == "CUP") "(?-i:CUP)" else Regex.escape(it.currencyCode)
                } +
                "|dollars?|euros?|pounds?|yen|yuan|won|rupees?|cents?|pence|currency)(?![\\p{L}\\p{N}])",
        )
        val COMMERCIAL = terms(
            "prices?|priced|pricing|costs?|costing|fees?|charges?|charged|charging|payments?|pay|paying|paid|" +
                "paypal|checkout|pledges?|authorize|authorization|holds?|captures?|refunds?|refunded|refundable|" +
                "reimbursements?|returns?|guarantee(?:s|d)?|guaranteed|warrant(?:y|ies)|promis(?:e[sd]?|ing)|" +
                "assur(?:e[sd]?|ance)|free|complimentary|discounts?|savings?|sale|sales|purchase[sd]?|buy|buying|" +
                "orders?|preorders?|fund(?:ed|ing|s)?|money|cash|tax(?:es)?|affordable|cheap|bargain|quantit(?:y|ies)|qty|units?|copies|pieces?|packs?|" +
                "bundles?|each|every|per|minimum|min|maximum|max|capacity|capacities|thresholds?|limits?|limited|" +
                "stock|sold|sell|selling|percent(?:age)?|profit|deposit|balance|subscription|installments?",
        )
        val DELIVERY = terms(
            "deliver(?:y|ies|ed|ing|s)?|ship(?:ping|ped|s)?|dispatch(?:ed|es|ing)?|arriv(?:e[sd]?|al|ing)|" +
                "receiv(?:e[sd]?|ing)|fulfill(?:ment|ed|s|ing)?|mail(?:ed|ing|s)?|send(?:s|ing)?|sent|turnaround|" +
                "deadlines?|due|expires?|expiry|expiration|" +
                "today|tomorrow|tonight|soon|later|before|until|within|ready|will|shall|expect(?:ed)?|" +
                "days?|weeks?|months?|years?|hours?|minutes?|seconds?|monday|tuesday|wednesday|thursday|friday|" +
                "saturday|sunday|january|february|march|april|may|june|july|august|september|october|november|december",
        )

        fun terms(values: String) = Regex("(?i)(?<![\\p{L}\\p{N}])(?:$values)(?![\\p{L}\\p{N}])")
    }
}

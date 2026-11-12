package app.campaign.importing

import java.util.Locale

/** Ignore case, spacing and one terminal period/exclamation; retain questions and internal punctuation. */
internal fun wishDuplicateKey(text: String): String {
    val normalized = text.replace(Regex("(?U)\\s+"), " ").trim().lowercase(Locale.ROOT)
    return if (normalized.length > 1 && normalized.last() in ".!" &&
        normalized[normalized.lastIndex - 1].isLetterOrDigit()
    ) normalized.dropLast(1) else normalized
}

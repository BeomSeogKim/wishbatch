package app.campaign.importing

/** Only spacing is normalized; case, punctuation, numbers, and words remain significant. */
internal fun wishDuplicateKey(text: String): String = text.trim().replace(Regex("(?U)\\s+"), " ")

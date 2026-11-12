package app.campaign.gloss

import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

data class GlossSource(val wishId: UUID, val sourceHash: String, val text: String)

class GlossRequest(val sources: List<GlossSource>) {
    val timeout: Duration = Duration.ofSeconds(15)
    val maxOutputTokens: Int = 2048

    init {
        require(sources.size in 1..20 && sources.map { it.wishId }.distinct().size == sources.size)
        require(sources.all { it.text.isNotBlank() && it.text.length <= 500 && it.sourceHash == glossSourceHash(it.text) })
    }
}

enum class GlossStatus { TRANSLATED, ENGLISH, UNCERTAIN }

data class GlossTranslation(
    val wishId: UUID,
    val sourceHash: String,
    val status: GlossStatus,
    val inferredLanguage: String?,
    val englishGloss: String?,
)

enum class GlossFailure { NOT_CONFIGURED, LIMIT_REACHED, TIMEOUT, INVALID_OUTPUT, PROVIDER_FAILURE }

sealed interface GlossResult {
    data class Completed(val model: String, val translations: List<GlossTranslation>) : GlossResult
    data class Failed(val reason: GlossFailure) : GlossResult
}

/** One request, no retry, within the request's time/token limits and the shared provider daily cap. */
fun interface WishGlossTranslator {
    fun translate(request: GlossRequest): GlossResult
}

fun glossSourceHash(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

fun validateGlosses(sources: List<GlossSource>, translations: List<GlossTranslation>) {
    val expected = sources.associateBy { it.wishId }
    require(translations.size == sources.size && translations.map { it.wishId }.toSet() == expected.keys)
    translations.forEach { translation ->
        require(translation.sourceHash == expected.getValue(translation.wishId).sourceHash)
        val language = translation.inferredLanguage
        require(language == null || Regex("[a-z]{2,8}(-[A-Za-z0-9]{2,8})*").matches(language) && language.length <= 40)
        when (translation.status) {
            GlossStatus.TRANSLATED -> {
                require(language != null && language.substringBefore('-') != "en")
                require(!translation.englishGloss.isNullOrBlank() && translation.englishGloss.length <= 1500)
                require(translation.englishGloss.none { it.isISOControl() && it !in "\n\r\t" })
            }
            GlossStatus.ENGLISH -> require(language == "en" && translation.englishGloss == null)
            GlossStatus.UNCERTAIN -> require(language == null && translation.englishGloss == null)
        }
    }
}

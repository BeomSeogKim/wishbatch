package app.campaign.gloss

import app.campaign.model.Wish
import java.lang.Character.UnicodeScript

enum class WishLanguageBasis { STORED_GLOSS, SCRIPT, UNKNOWN }

data class WishLanguage(val tag: String?, val basis: WishLanguageBasis)

/** Inferred metadata only, never evidence. SCRIPT identifies a script-based fallback, not verified language. */
fun wishLanguage(wish: Wish, savedGloss: WishGloss? = null): WishLanguage {
    val sourceHash = glossSourceHash(wish.text)
    val status = savedGloss?.status
    if (savedGloss != null && savedGloss.workspaceId == wish.workspaceId && savedGloss.wishId == wish.id &&
        savedGloss.sourceHash == sourceHash && status != null
    ) {
        val translation = GlossTranslation(wish.id, sourceHash, status,
            savedGloss.inferredLanguage, savedGloss.englishGloss)
        val valid = try {
            validateGlosses(listOf(GlossSource(wish.id, sourceHash, wish.text)), listOf(translation))
            true
        } catch (_: IllegalArgumentException) {
            false
        }
        val tag = translation.inferredLanguage
        if (valid && tag != null && tag.substringBefore('-') !in setOf("und", "mul", "zxx")) {
            return WishLanguage(tag, WishLanguageBasis.STORED_GLOSS)
        }
    }

    val scripts = wish.text.codePoints().filter { Character.isLetter(it) }
        .mapToObj { UnicodeScript.of(it) }.toList().toSet()
    val tag = when {
        scripts == setOf(UnicodeScript.HANGUL) -> "ko"
        scripts.any { it == UnicodeScript.HIRAGANA || it == UnicodeScript.KATAKANA } &&
            scripts.all { it in setOf(UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA, UnicodeScript.HAN, UnicodeScript.COMMON) } -> "ja"
        else -> null
    }
    return WishLanguage(tag, if (tag == null) WishLanguageBasis.UNKNOWN else WishLanguageBasis.SCRIPT)
}

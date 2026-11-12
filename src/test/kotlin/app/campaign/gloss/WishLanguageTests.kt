package app.campaign.gloss

import app.campaign.model.Wish
import app.campaign.model.WishSource
import app.campaign.workspace.SeedCatalog
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class WishLanguageTests {
    private val unknown = WishLanguage(null, WishLanguageBasis.UNKNOWN)

    @Test
    fun `clear Hangul and Kana have an explicit script basis`() {
        listOf("\uD55C\uAE00", "\u1112\u1161\u11AB", "\uD55C\uAE00 123! \uD83D\uDE0A").forEach {
            assertEquals(WishLanguage("ko", WishLanguageBasis.SCRIPT), wishLanguage(wish(it)))
        }
        listOf("\u304B\u306A", "\u30AB\u30FC\u30C9", "\u6F22\u5B57\u304B\u306A").forEach {
            assertEquals(WishLanguage("ja", WishLanguageBasis.SCRIPT), wishLanguage(wish(it)))
        }
    }

    @Test
    fun `Latin Han shared scripts mixed scripts and nonwords remain unknown`() {
        listOf("I want cards", "Quiero una postal", "Je pr\u00E9f\u00E8re l\u2019affiche", "\u6F22\u5B57",
            "\uD55C\uAE00 cards", "\uD55C\uAE00\u304B\u306A", "\uD55C\uAE00\u6F22\u5B57",
            "\u0628\u0627", "\u0414\u0430", "", "123 \uD83D\uDE0A", "\u30FC").forEach {
            assertEquals(unknown, wishLanguage(wish(it)), it)
        }
    }

    @Test
    fun `validated current gloss supplies language before script fallback`() {
        val source = wish("Quiero una postal")
        assertEquals(WishLanguage("es", WishLanguageBasis.STORED_GLOSS), wishLanguage(source, gloss(source)))
        val korean = wish("\uD55C\uAE00")
        assertEquals(WishLanguage("ko-KR", WishLanguageBasis.STORED_GLOSS),
            wishLanguage(korean, gloss(korean).apply { inferredLanguage = "ko-KR" }))
        val english = wish("I want cards")
        assertEquals(WishLanguage("en", WishLanguageBasis.STORED_GLOSS),
            wishLanguage(english, gloss(english).apply {
                status = GlossStatus.ENGLISH; inferredLanguage = "en"; englishGloss = null
            }))
    }

    @Test
    fun `stale gloss and foreign identities cannot attach language even for identical text`() {
        val source = wish("Quiero una postal")
        val saved = gloss(source)
        val changed = Wish(source.workspaceId, source.text + " ", source.source, createdAt = source.createdAt, id = source.id)
        assertEquals(unknown, wishLanguage(changed, saved))
        val duplicate = Wish(source.workspaceId, source.text, source.source, createdAt = source.createdAt)
        assertEquals(unknown, wishLanguage(duplicate, saved))
        val foreign = Wish(UUID.randomUUID(), source.text, source.source, createdAt = source.createdAt, id = source.id)
        assertEquals(unknown, wishLanguage(foreign, saved))
    }

    @Test
    fun `invalid incomplete and uncertain gloss metadata cannot supply a language`() {
        val source = wish("Quiero una postal")
        listOf<WishGloss.() -> Unit>(
            { status = null },
            { status = GlossStatus.UNCERTAIN },
            { status = GlossStatus.ENGLISH },
            { inferredLanguage = "Spanish" },
            { inferredLanguage = null },
            { englishGloss = null },
            { englishGloss = "x".repeat(1501) },
            { englishGloss = "invalid\u0000" },
            { inferredLanguage = "und" },
            { inferredLanguage = "mul" },
            { inferredLanguage = "zxx" },
        ).forEach { invalidate -> assertEquals(unknown, wishLanguage(source, gloss(source).apply(invalidate))) }
    }

    @Test
    fun `unavailable gloss still allows fallback from the current original only`() {
        val source = wish("\uD55C\uAE00")
        val stale = WishGloss(source.workspaceId, source.id, glossSourceHash("previous text"),
            status = GlossStatus.TRANSLATED, inferredLanguage = "es", englishGloss = "Cards")
        assertEquals(WishLanguage("ko", WishLanguageBasis.SCRIPT), wishLanguage(source, stale))
        assertEquals(WishLanguage("ko", WishLanguageBasis.SCRIPT),
            wishLanguage(source, gloss(source).apply {
                status = GlossStatus.UNCERTAIN; inferredLanguage = null; englishGloss = null
            }))
    }

    @Test
    fun `each current synthetic set preserves exact originals and Latin uncertainty`() {
        val catalog = SeedCatalog()
        catalog.scenarios.forEach { scenario ->
            val sources = scenario.wishes.map { wish(it.text) }
            val originals = sources.map { it.text }
            val tags = sources.map { wishLanguage(it).tag }
            assertEquals(if (scenario.key == "illustrator") 2 else 1, tags.count { it == "ko" })
            assertEquals(sources.size, tags.count { it == null || it == "ko" })
            assertEquals(originals, sources.map { it.text })
        }
    }

    private fun wish(text: String) = Wish(UUID.randomUUID(), text, WishSource.IMPORTED, createdAt = Instant.EPOCH)

    private fun gloss(wish: Wish) = WishGloss(wish.workspaceId, wish.id, glossSourceHash(wish.text),
        status = GlossStatus.TRANSLATED, inferredLanguage = "es", englishGloss = "I want a postcard")
}

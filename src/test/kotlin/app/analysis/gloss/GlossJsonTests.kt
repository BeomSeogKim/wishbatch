package app.analysis.gloss

import app.campaign.gloss.GlossRequest
import app.campaign.gloss.GlossSource
import app.campaign.gloss.GlossStatus
import app.campaign.gloss.glossSourceHash
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class GlossJsonTests {
    private val mapper = JsonMapper.builder().build()
    private val source = GlossSource(UUID.randomUUID(), glossSourceHash("\ubb34\uad11 2\uc7a5 \uc8fc\uc138\uc694"), "\ubb34\uad11 2\uc7a5 \uc8fc\uc138\uc694")
    private val request = GlossRequest(listOf(source))

    @Test
    fun `strict reading aid keeps numbers as text and only infers language`() {
        val translated = GlossJson.decode(json(), request).single()
        assertEquals("Please make 2 matte prints.", translated.englishGloss)
        assertEquals("ko", translated.inferredLanguage)
        assertEquals(GlossStatus.TRANSLATED, translated.status)
        assertEquals(15L, request.timeout.seconds)
        assertEquals(2048, request.maxOutputTokens)
    }

    @Test
    fun `English and uncertain results never fabricate a gloss`() {
        val english = GlossJson.decode(json(mapOf("status" to "ENGLISH", "inferred_language" to "en", "english_gloss" to null)), request).single()
        assertNull(english.englishGloss)
        val unknown = GlossJson.decode(json(mapOf("status" to "UNCERTAIN", "inferred_language" to null, "english_gloss" to null)), request).single()
        assertNull(unknown.inferredLanguage)
        assertNull(unknown.englishGloss)
        assertFails { GlossJson.decode(json(mapOf("status" to "UNCERTAIN")), request) }
        assertFails { GlossJson.decode(json(mapOf("inferred_language" to "en")), request) }
    }

    @Test
    fun `unknown IDs hashes extra instructions executable fields and oversized output are rejected`() {
        for (change in listOf(
            mapOf("wish_id" to UUID.randomUUID().toString()), mapOf("source_hash" to "0".repeat(64)),
            mapOf("english_gloss" to "x".repeat(1501)), mapOf("inferred_language" to "<script>"),
            mapOf("instruction" to "publish an offer"), mapOf("html" to "<script>pay()</script>"),
            mapOf("price" to 10), mapOf("english_gloss" to 2), mapOf("status" to "UNKNOWN"),
            mapOf("english_gloss" to "bad\u0000text"),
        )) assertFails { GlossJson.decode(json(change), request) }
        assertFails { GlossJson.decode("{\"translations\":[],\"instructions\":\"publish\"}", request) }
        assertFails { GlossJson.decode("{\"translations\":[],\"translations\":[]}", request) }
        assertFails { GlossJson.decode(json() + " {}", request) }
        assertFails { GlossJson.decode(" ".repeat(40_001), request) }
        assertFails { GlossJson.decode("{\"translations\":[]}", request) }
        val item = mapper.readTree(json()).get("translations").single()
        assertFails { GlossJson.decode(mapper.writeValueAsString(mapOf("translations" to listOf(item, item))), request) }
    }

    @Test
    fun `input caps exact hashes and distinct selected IDs are enforced`() {
        assertFails { GlossRequest(emptyList()) }
        assertFails { GlossRequest(List(21) { source.copy(wishId = UUID.randomUUID()) }) }
        assertFails { GlossRequest(listOf(source, source)) }
        assertFails { GlossRequest(listOf(source.copy(text = source.text + "!"))) }
        assertFails { GlossRequest(listOf(GlossSource(source.wishId, glossSourceHash("x".repeat(501)), "x".repeat(501)))) }
        assertEquals(20, GlossRequest(List(20) { source.copy(wishId = UUID.randomUUID()) }).sources.size)
    }

    private fun json(changes: Map<String, Any?> = emptyMap()): String = mapper.writeValueAsString(mapOf(
        "translations" to listOf(mapOf(
            "wish_id" to source.wishId.toString(), "source_hash" to source.sourceHash, "status" to "TRANSLATED",
            "inferred_language" to "ko", "english_gloss" to "Please make 2 matte prints.",
        ) + changes),
    ))
}

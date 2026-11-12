package app.analysis.gloss

import app.campaign.gloss.GlossRequest
import app.campaign.gloss.GlossStatus
import app.campaign.gloss.GlossTranslation
import app.campaign.gloss.validateGlosses
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

/** Reading aids contain no evidence, verdict, action, or commercial fields. */
object GlossJson {
    private val mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()
    private val fields = setOf("wish_id", "source_hash", "status", "inferred_language", "english_gloss")

    fun decode(json: String, request: GlossRequest): List<GlossTranslation> {
        require(json.length <= 40_000)
        val root = mapper.readTree(json)
        require(root.isObject && root.propertyNames().toSet() == setOf("translations"))
        val translations = root.get("translations")
        require(translations.isArray && translations.size() == request.sources.size)
        val result = translations.toList().map { item ->
            require(item.isObject && item.propertyNames().toSet() == fields)
            require(listOf("wish_id", "source_hash", "status").all { item.get(it).isString })
            require(listOf("inferred_language", "english_gloss").all { item.get(it).isNull || item.get(it).isString })
            GlossTranslation(
                java.util.UUID.fromString(item.get("wish_id").asString()), item.get("source_hash").asString(),
                GlossStatus.valueOf(item.get("status").asString()),
                item.get("inferred_language").takeUnless { it.isNull }?.asString(),
                item.get("english_gloss").takeUnless { it.isNull }?.asString(),
            )
        }
        validateGlosses(request.sources, result)
        return result
    }
}

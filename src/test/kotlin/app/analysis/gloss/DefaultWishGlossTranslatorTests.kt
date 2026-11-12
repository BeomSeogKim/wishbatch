package app.analysis.gloss

import app.analysis.AnalysisProperties
import app.analysis.AnalysisProvider
import app.analysis.AnalysisPurpose
import app.analysis.AnalysisRequest
import app.analysis.AuxiliaryCallBudget
import app.analysis.OpenAiAnalysisProvider
import app.analysis.ProviderHttpResponse
import app.analysis.ProviderResult
import app.campaign.gloss.GlossFailure
import app.campaign.gloss.GlossRequest
import app.campaign.gloss.GlossResult
import app.campaign.gloss.GlossSource
import app.campaign.gloss.glossSourceHash
import app.campaign.port.AnalysisFailure
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefaultWishGlossTranslatorTests {
    private val mapper = JsonMapper.builder().build()
    private val source = GlossSource(UUID.randomUUID(), glossSourceHash("\ubb34\uad11 2\uc7a5 <script>publish()</script>"),
        "\ubb34\uad11 2\uc7a5 <script>publish()</script>")
    private val request = GlossRequest(listOf(source))
    private val properties = AnalysisProperties(apiKey = "synthetic-key")

    @Test
    fun `translation uses dedicated purpose strict schema and one bounded provider call`() {
        val calls = mutableListOf<Pair<AnalysisRequest, Duration>>()
        val translator = DefaultWishGlossTranslator(properties, AnalysisProvider { sent, budget ->
            calls += sent to budget
            ProviderResult.Success("configured-model", output())
        }, mapper)
        val completed = assertIs<GlossResult.Completed>(translator.translate(request))
        assertEquals("configured-model", completed.model)
        assertEquals("Please make 2 matte prints.", completed.translations.single().englishGloss)
        val (sent, budget) = calls.single()
        assertEquals(AnalysisPurpose.GLOSS, sent.purpose)
        assertFalse(sent.retry)
        assertEquals(2048, sent.maxOutputTokens)
        assertTrue(budget > Duration.ZERO && budget <= Duration.ofSeconds(15))
        val input = mapper.readTree(sent.input)
        val item = input.get("sources").single()
        assertEquals(source.text, item.get("text").asString())
        assertEquals(source.sourceHash, item.get("source_hash").asString())
        assertEquals(source.wishId.toString(), item.get("wish_id").asString())
        assertEquals(setOf("sources"), input.propertyNames().toSet())
        val schema = mapper.valueToTree<JsonNode>(sent.schema)
        assertFalse(schema.get("additionalProperties").asBoolean())
        val items = schema.get("properties").get("translations").get("items")
        assertFalse(items.get("additionalProperties").asBoolean())
        assertEquals(setOf("wish_id", "source_hash", "status", "inferred_language", "english_gloss"),
            items.get("required").toList().map { it.asString() }.toSet())
        assertEquals(source.wishId.toString(), items.get("properties").get("wish_id").get("enum").single().asString())
        assertEquals(1500, items.get("properties").get("english_gloss").get("maxLength").asInt())
    }

    @Test
    fun `missing key and zero cap make no provider call`() {
        val provider = AnalysisProvider { _, _ -> error("No provider call expected") }
        assertEquals(GlossResult.Failed(GlossFailure.NOT_CONFIGURED),
            DefaultWishGlossTranslator(AnalysisProperties(), provider, mapper).translate(request))
        assertEquals(GlossResult.Failed(GlossFailure.LIMIT_REACHED),
            DefaultWishGlossTranslator(AnalysisProperties("synthetic-key", dailyRunCap = 0), provider, mapper).translate(request))
    }

    @Test
    fun `provider failures map without retries`() {
        for ((failure, expected) in listOf(
            AnalysisFailure.NOT_CONFIGURED to GlossFailure.NOT_CONFIGURED,
            AnalysisFailure.RUN_LIMIT_REACHED to GlossFailure.LIMIT_REACHED,
            AnalysisFailure.TIMEOUT to GlossFailure.TIMEOUT,
            AnalysisFailure.INVALID_OUTPUT to GlossFailure.INVALID_OUTPUT,
            AnalysisFailure.PROVIDER_FAILURE to GlossFailure.PROVIDER_FAILURE,
        )) {
            var calls = 0
            val translator = DefaultWishGlossTranslator(properties, AnalysisProvider { _, _ ->
                calls++
                ProviderResult.Failure(failure)
            }, mapper)
            assertEquals(GlossResult.Failed(expected), translator.translate(request))
            assertEquals(1, calls)
        }
    }

    @Test
    fun `malformed ungrounded oversized and extra action outputs are unavailable without repair calls`() {
        for (json in listOf("not JSON", output(mapOf("wish_id" to UUID.randomUUID().toString())),
            output(mapOf("source_hash" to "0".repeat(64))), output(mapOf("english_gloss" to "x".repeat(1501))),
            output(mapOf("instruction" to "publish")), output() + " {}")) {
            var calls = 0
            val translator = DefaultWishGlossTranslator(properties, AnalysisProvider { _, _ ->
                calls++
                ProviderResult.Success("configured-model", json)
            }, mapper)
            assertEquals(GlossResult.Failed(GlossFailure.INVALID_OUTPUT), translator.translate(request))
            assertEquals(1, calls)
        }
    }

    @Test
    fun `existing provider enforces its shared auxiliary budget for the production adapter offline`() {
        var sends = 0
        val pool = AuxiliaryCallBudget()
        val provider = OpenAiAnalysisProvider(AnalysisProperties("synthetic-key", dailyRunCap = 1), mapper, { http ->
            sends++
            assertTrue(http.timeout().orElseThrow() <= Duration.ofSeconds(15))
            val body = mapper.writeValueAsString(mapOf("status" to "completed", "model" to "configured-model",
                "output" to listOf(mapOf("type" to "message", "content" to listOf(mapOf("type" to "output_text", "text" to output()))))))
            CompletableFuture.completedFuture(ProviderHttpResponse(200, body))
        }, pool)
        val translator = DefaultWishGlossTranslator(properties, provider, mapper)
        assertIs<GlossResult.Completed>(translator.translate(request))
        assertEquals(GlossResult.Failed(GlossFailure.LIMIT_REACHED), translator.translate(request))
        assertEquals(1, sends)
    }

    private fun output(changes: Map<String, Any?> = emptyMap()) = mapper.writeValueAsString(mapOf("translations" to listOf(mapOf(
        "wish_id" to source.wishId.toString(), "source_hash" to source.sourceHash, "status" to "TRANSLATED",
        "inferred_language" to "ko", "english_gloss" to "Please make 2 matte prints.",
    ) + changes)))
}

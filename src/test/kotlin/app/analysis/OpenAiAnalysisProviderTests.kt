package app.analysis

import app.campaign.port.AnalysisFailure
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiAnalysisProviderTests {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val request = AnalysisRequest("{\"options\":[],\"wishes\":[]}", AnalysisPrompt.schema(emptyList()), false)
    private val response = """{"status":"completed","error":null,"model":"reported-model",
        "output":[{"type":"reasoning","summary":[]},{"type":"message",
        "content":[{"type":"output_text","text":"{}"}]}]}"""

    @Test
    fun `request uses Responses strict schema and only returns completed output text`() {
        var sent: HttpRequest? = null
        val provider = provider { sent = it; CompletableFuture.completedFuture(ProviderHttpResponse(200, response)) }
        val result = assertIs<ProviderResult.Success>(provider.generate(request, Duration.ofSeconds(42)))
        assertEquals("reported-model", result.model)
        assertEquals("{}", result.json)
        val http = requireNotNull(sent)
        assertEquals("https://api.openai.com/v1/responses", http.uri().toString())
        assertEquals("POST", http.method())
        assertEquals(Duration.ofSeconds(42), http.timeout().orElseThrow())
        val body = mapper.readTree(body(http))
        assertEquals("gpt-6.1-sol", body["model"].asString())
        assertFalse(body["store"].asBoolean())
        assertEquals(16000, body["max_output_tokens"].asInt())
        assertEquals("json_schema", body["text"]["format"]["type"].asString())
        assertTrue(body["text"]["format"]["strict"].asBoolean())
        assertFalse(body["text"]["format"]["schema"]["additionalProperties"].asBoolean())
        assertEquals(request.input, body["input"][0]["content"].asString())
        assertTrue(body["instructions"].asString().contains("NOT_A_REQUEST"))
        assertFalse(body.has("tools"))
    }

    @Test
    fun `strict schema requires every property and forbids unknown keys recursively`() {
        fun check(node: tools.jackson.databind.JsonNode) {
            if (node.path("type").asString("") == "object") {
                assertFalse(node["additionalProperties"].asBoolean())
                val keys: Set<String> = node["properties"].properties().map { it.key }.toSet()
                val required: Set<String> = node["required"].toList().map { it.asString() }.toSet()
                assertEquals(keys, required)
            }
            if ((node.isObject || node.isArray)) node.forEach { check(it) }
        }
        check(mapper.valueToTree(AnalysisPrompt.schema(emptyList())))
    }

    @Test
    fun `HTTP errors cannot leak provider messages or credentials`() {
        listOf(400, 401, 429, 500, 503).forEach { status ->
            val provider = provider { CompletableFuture.completedFuture(ProviderHttpResponse(status,
                """{"error":{"type":"invalid_request_error","code":"invalid_api_key","message":"private detail"}}""")) }
            assertEquals(ProviderResult.Failure(AnalysisFailure.PROVIDER_FAILURE), provider.generate(request, Duration.ofSeconds(1)))
        }
    }

    @Test
    fun `refusal incomplete error and malformed envelope are provider failures`() {
        listOf(
            response.replace("completed", "incomplete"),
            response.replace("\"error\":null", "\"error\":{\"code\":\"server_error\",\"message\":\"private detail\"}"),
            response.replace("output_text", "refusal"),
            "{", "{}",
        ).forEach { body ->
            val provider = provider { CompletableFuture.completedFuture(ProviderHttpResponse(200, body)) }
            assertEquals(ProviderResult.Failure(AnalysisFailure.PROVIDER_FAILURE), provider.generate(request, Duration.ofSeconds(1)))
        }
    }

    @Test
    fun `timeout cancels in flight request and transport timeout stays typed`() {
        val pending = CompletableFuture<ProviderHttpResponse>()
        assertEquals(ProviderResult.Failure(AnalysisFailure.TIMEOUT),
            provider { pending }.generate(request, Duration.ofNanos(1)))
        assertTrue(pending.isCancelled)
        assertEquals(ProviderResult.Failure(AnalysisFailure.TIMEOUT),
            provider { CompletableFuture.failedFuture(HttpTimeoutException("private detail")) }
                .generate(request, Duration.ofSeconds(1)))
    }

    @Test
    fun `missing key and exhausted budget do not touch transport`() {
        val transport = AnalysisTransport { error("Must not call transport") }
        assertEquals(ProviderResult.Failure(AnalysisFailure.NOT_CONFIGURED),
            OpenAiAnalysisProvider(AnalysisProperties(), mapper, transport).generate(request, Duration.ofSeconds(1)))
        assertEquals(ProviderResult.Failure(AnalysisFailure.TIMEOUT),
            OpenAiAnalysisProvider(AnalysisProperties("test"), mapper, transport).generate(request, Duration.ZERO))
        assertEquals(ProviderResult.Failure(AnalysisFailure.PROVIDER_FAILURE),
            OpenAiAnalysisProvider(AnalysisProperties("invalid\nheader"), mapper, transport).generate(request, Duration.ofSeconds(1)))
    }

    private fun provider(send: (HttpRequest) -> CompletableFuture<ProviderHttpResponse>) =
        OpenAiAnalysisProvider(AnalysisProperties("test"), mapper, AnalysisTransport(send))

    private fun body(request: HttpRequest): String {
        val bytes = java.io.ByteArrayOutputStream()
        request.bodyPublisher().orElseThrow().subscribe(object : Flow.Subscriber<ByteBuffer> {
            override fun onSubscribe(subscription: Flow.Subscription) { subscription.request(Long.MAX_VALUE) }
            override fun onNext(item: ByteBuffer) {
                val chunk = ByteArray(item.remaining())
                item.get(chunk)
                bytes.write(chunk)
            }
            override fun onError(throwable: Throwable) { throw throwable }
            override fun onComplete() = Unit
        })
        return bytes.toString(Charsets.UTF_8)
    }
}

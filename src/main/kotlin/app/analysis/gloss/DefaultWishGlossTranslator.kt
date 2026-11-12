package app.analysis.gloss

import app.analysis.AnalysisProperties
import app.analysis.AnalysisProvider
import app.analysis.AnalysisPurpose
import app.analysis.AnalysisRequest
import app.analysis.OpenAiAnalysisProvider
import app.analysis.ProviderResult
import app.campaign.gloss.GlossFailure
import app.campaign.gloss.GlossRequest
import app.campaign.gloss.GlossResult
import app.campaign.gloss.WishGlossTranslator
import app.campaign.port.AnalysisFailure
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

class DefaultWishGlossTranslator(
    private val properties: AnalysisProperties,
    private val provider: AnalysisProvider,
    private val mapper: JsonMapper,
) : WishGlossTranslator {
    override fun translate(request: GlossRequest): GlossResult {
        if (properties.dailyRunCap == 0) return GlossResult.Failed(GlossFailure.LIMIT_REACHED)
        if (properties.apiKey.isBlank()) return GlossResult.Failed(GlossFailure.NOT_CONFIGURED)
        val started = System.nanoTime()
        val input = mapper.writeValueAsString(mapOf("sources" to request.sources.map {
            mapOf("wish_id" to it.wishId, "source_hash" to it.sourceHash, "text" to it.text)
        }))
        val schema = GlossJson.schema(request)
        val remaining = request.timeout.toNanos() - (System.nanoTime() - started)
        if (remaining <= 0) return GlossResult.Failed(GlossFailure.TIMEOUT)
        val result = provider.generate(AnalysisRequest(input, schema, retry = false,
            maxOutputTokens = request.maxOutputTokens, purpose = AnalysisPurpose.GLOSS), Duration.ofNanos(remaining))
        if (result is ProviderResult.Failure) return GlossResult.Failed(when (result.reason) {
            AnalysisFailure.NOT_CONFIGURED -> GlossFailure.NOT_CONFIGURED
            AnalysisFailure.RUN_LIMIT_REACHED -> GlossFailure.LIMIT_REACHED
            AnalysisFailure.TIMEOUT -> GlossFailure.TIMEOUT
            AnalysisFailure.INVALID_OUTPUT -> GlossFailure.INVALID_OUTPUT
            else -> GlossFailure.PROVIDER_FAILURE
        })
        result as ProviderResult.Success
        val translations = try {
            require(result.model.isNotBlank() && result.model.length <= 120)
            GlossJson.decode(result.json, request)
        } catch (_: JacksonException) {
            return GlossResult.Failed(GlossFailure.INVALID_OUTPUT)
        } catch (_: IllegalArgumentException) {
            return GlossResult.Failed(GlossFailure.INVALID_OUTPUT)
        }
        if (System.nanoTime() - started >= request.timeout.toNanos()) return GlossResult.Failed(GlossFailure.TIMEOUT)
        return GlossResult.Completed(result.model, translations)
    }
}

@Configuration(proxyBeanMethods = false)
class GlossAnalysisConfiguration {
    @Bean
    fun wishGlossTranslator(properties: AnalysisProperties, mapper: JsonMapper): WishGlossTranslator =
        DefaultWishGlossTranslator(properties, OpenAiAnalysisProvider(properties, mapper), mapper)
}

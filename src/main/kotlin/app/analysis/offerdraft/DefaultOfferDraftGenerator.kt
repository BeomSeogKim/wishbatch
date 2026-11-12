package app.analysis.offerdraft

import app.analysis.AnalysisProperties
import app.analysis.AnalysisProvider
import app.analysis.AnalysisPurpose
import app.analysis.AnalysisRequest
import app.analysis.OpenAiAnalysisProvider
import app.analysis.ProviderResult
import app.campaign.offerdraft.OfferDraftGeneration
import app.campaign.offerdraft.OfferDraftGenerator
import app.campaign.offerdraft.OfferDraftInput
import app.campaign.offerdraft.OfferDraftValidator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

class DefaultOfferDraftGenerator(
    private val provider: AnalysisProvider,
    private val mapper: JsonMapper,
) : OfferDraftGenerator {
    override fun generate(input: OfferDraftInput, timeout: Duration, maxOutputTokens: Int): OfferDraftGeneration {
        if (timeout.isZero || timeout.isNegative || timeout > Duration.ofSeconds(15) || maxOutputTokens != 1536 ||
            input.facts.isEmpty() || input.facts.size > 30 || input.facts.any { it.length > 500 } ||
            input.evidence.isEmpty() || input.evidence.size > 10 || input.evidence.any { it.quote.isBlank() || it.quote.length > 500 }
        ) return OfferDraftGeneration.Unavailable("INVALID_INPUT")
        val json = mapper.writeValueAsString(input)
        if (json.length > 16000) return OfferDraftGeneration.Unavailable("INVALID_INPUT")
        val request = AnalysisRequest(json, OfferDraftValidator(mapper).schema(), retry = false,
            maxOutputTokens = 1536, purpose = AnalysisPurpose.OFFER_COPY)
        return when (val result = provider.generate(request, timeout)) {
            is ProviderResult.Success -> OfferDraftGeneration.Generated(result.json, result.model)
            is ProviderResult.Failure -> OfferDraftGeneration.Unavailable(result.reason.name)
        }
    }
}

@Configuration(proxyBeanMethods = false)
class OfferDraftConfiguration {
    @Bean
    fun offerDraftGenerator(properties: AnalysisProperties, mapper: JsonMapper): OfferDraftGenerator =
        DefaultOfferDraftGenerator(OpenAiAnalysisProvider(properties, mapper), mapper)
}

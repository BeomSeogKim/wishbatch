package app.analysis

import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.port.AnalysisFailure
import app.campaign.port.AnalysisResult
import app.campaign.port.AnalysisValidationFlag
import app.campaign.port.AnalysisValidationResult
import app.campaign.port.WishAnalyzer
import app.campaign.rules.analysisInputHash
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

class DefaultWishAnalyzer(
    private val properties: AnalysisProperties,
    private val provider: AnalysisProvider,
    private val mapper: JsonMapper,
    private val clock: Clock = Clock.systemUTC(),
    private val nanoTime: () -> Long = System::nanoTime,
) : WishAnalyzer {
    private val validator = AnalysisValidator(mapper)
    private var capDate: LocalDate? = null
    private var callsToday = 0

    override fun analyze(options: List<Option>, wishes: List<Wish>): AnalysisResult {
        val started = nanoTime()
        if (properties.apiKey.isBlank()) return AnalysisResult.Failure(AnalysisFailure.NOT_CONFIGURED)
        if (wishes.size > 200 || wishes.any { it.text.length > 500 } ||
            wishes.map { it.id }.distinct().size != wishes.size || options.map { it.id }.distinct().size != options.size
        ) return AnalysisResult.Failure(AnalysisFailure.INVALID_OUTPUT)
        val input = AnalysisPrompt.input(mapper, options, wishes)
        if (input.length > 160000) return AnalysisResult.Failure(AnalysisFailure.INVALID_OUTPUT)
        val hash = analysisInputHash(options, wishes)
        val schema = AnalysisPrompt.schema(options)
        val flags = mutableListOf<AnalysisValidationFlag>()
        var lastFailure = AnalysisFailure.INVALID_OUTPUT
        repeat(2) { attempt ->
            val remaining = Duration.ofNanos(Duration.ofSeconds(60).toNanos() - (nanoTime() - started))
            if (remaining.isNegative || remaining.isZero) return AnalysisResult.Failure(AnalysisFailure.TIMEOUT, flags.toList())
            if (!reserveCall()) return AnalysisResult.Failure(AnalysisFailure.RUN_LIMIT_REACHED, flags.toList())
            val result = provider.generate(AnalysisRequest(input, schema, retry = attempt == 1), remaining)
            if (nanoTime() - started >= Duration.ofSeconds(60).toNanos()) {
                return AnalysisResult.Failure(AnalysisFailure.TIMEOUT, flags.toList())
            }
            when (result) {
                is ProviderResult.Failure -> return AnalysisResult.Failure(result.reason, flags.toList())
                is ProviderResult.Success -> {
                    val validated = try {
                        validator.validate(result.json, options, wishes)
                    } catch (_: JacksonException) {
                        lastFailure = AnalysisFailure.INVALID_JSON
                        null
                    }
                    if (nanoTime() - started >= Duration.ofSeconds(60).toNanos()) {
                        return AnalysisResult.Failure(AnalysisFailure.TIMEOUT, flags.toList())
                    }
                    when (validated) {
                        is AnalysisValidationResult.Accepted -> return AnalysisResult.Success(
                            model = result.model,
                            validated = validated.copy(flags = (flags + validated.flags).toList()),
                            inputHash = hash,
                            completedAt = clock.instant(),
                        )
                        is AnalysisValidationResult.Rejected -> {
                            flags.addAll(validated.flags)
                            lastFailure = AnalysisFailure.INVALID_OUTPUT
                        }
                        null -> Unit
                    }
                }
            }
        }
        return AnalysisResult.Failure(lastFailure, flags.toList())
    }

    @Synchronized
    private fun reserveCall(): Boolean {
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        if (capDate != today) {
            capDate = today
            callsToday = 0
        }
        if (callsToday >= properties.dailyRunCap) return false
        callsToday++
        return true
    }
}

@Configuration(proxyBeanMethods = false)
class AnalysisConfiguration {
    @Bean
    fun analysisProperties(environment: Environment): AnalysisProperties = AnalysisProperties.from(environment)

    @Bean
    fun wishAnalyzer(properties: AnalysisProperties, mapper: JsonMapper): WishAnalyzer =
        DefaultWishAnalyzer(properties, OpenAiAnalysisProvider(properties, mapper), mapper)
}

package app.analysis

import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.port.AnalysisResult
import app.campaign.port.WishAnalyzer
import app.campaign.rules.optionWishCounts
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@Component
@ConditionalOnProperty(name = ["LLM_SEED_RUN"], havingValue = "true")
class SeedRunGenerator(
    private val analyzer: WishAnalyzer,
    private val mapper: JsonMapper,
    private val environment: Environment,
    private val context: ConfigurableApplicationContext,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val optionsFile = Path.of(environment.getRequiredProperty("LLM_SEED_OPTIONS_FILE"))
        val wishesFile = Path.of(environment.getRequiredProperty("LLM_SEED_WISHES_FILE"))
        val outputFile = Path.of(environment.getRequiredProperty("LLM_SEED_OUTPUT_FILE"))
        check(!Files.exists(outputFile)) { "Seed output already exists" }
        val options = mapper.readValue(optionsFile.toFile(), object : TypeReference<List<Option>>() {})
        val wishes = mapper.readValue(wishesFile.toFile(), object : TypeReference<List<Wish>>() {})
        val started = System.nanoTime()
        when (val result = analyzer.analyze(options, wishes)) {
            is AnalysisResult.Failure -> error("Seed analysis failed: ${result.reason}")
            is AnalysisResult.Success -> {
                Files.writeString(outputFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                println("Seed analysis model=${result.model} latencyMs=${(System.nanoTime() - started) / 1000000}")
                println("Validation flags=${result.validated.flags.map { it.rule }}")
                println("Option counts=${optionWishCounts(result.validated.output)}")
            }
        }
        context.close()
    }
}

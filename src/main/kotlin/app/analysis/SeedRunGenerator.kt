package app.analysis

import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishSource
import app.campaign.workspace.SeedCatalog
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
import java.time.Instant
import java.util.UUID

@Component
@ConditionalOnProperty(name = ["LLM_SEED_RUN"], havingValue = "true")
class SeedRunGenerator(
    private val analyzer: WishAnalyzer,
    private val mapper: JsonMapper,
    private val environment: Environment,
    private val context: ConfigurableApplicationContext,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val outputFile = Path.of(environment.getRequiredProperty("LLM_SEED_OUTPUT_FILE"))
        check(!Files.exists(outputFile)) { "Seed output already exists" }
        val optionsFile = environment.getProperty("LLM_SEED_OPTIONS_FILE")
        val wishesFile = environment.getProperty("LLM_SEED_WISHES_FILE")
        require((optionsFile == null) == (wishesFile == null)) { "Provide both snapshot files or neither" }
        val catalog = if (optionsFile == null) SeedCatalog() else null
        val workspaceId = UUID(0, 0)
        val options = if (catalog != null) catalog.definition.options.map {
            Option(workspaceId, it.name, it.format, it.fixedAttributes, it.allowedVariants,
                it.unitCost, it.currency, it.minBatch, it.maxCapacity, it.leadTimeText, it.shipRegion,
                catalog.optionId(workspaceId, it.key))
        } else mapper.readValue(Path.of(optionsFile!!).toFile(), object : TypeReference<List<Option>>() {})
        val wishes = if (catalog != null) catalog.wishes.map {
            Wish(workspaceId, it.text, WishSource.IMPORTED, displayName = it.displayName,
                createdAt = Instant.EPOCH, id = catalog.wishId(workspaceId, it.key))
        } else mapper.readValue(Path.of(wishesFile!!).toFile(), object : TypeReference<List<Wish>>() {})
        val started = System.nanoTime()
        when (val result = analyzer.analyze(options, wishes)) {
            is AnalysisResult.Failure -> error("Seed analysis failed: ${result.reason}")
            is AnalysisResult.Success -> {
                check(result.validated.output.wishes.map { it.wishId }.toSet() == wishes.map { it.id }.toSet()) {
                    "Stored seed must contain every wish"
                }
                val stored = mapper.valueToTree<tools.jackson.databind.node.ObjectNode>(result)
                if (catalog != null) {
                    stored.put("seedWorkspaceId", workspaceId.toString())
                    stored.set("optionKeys", mapper.valueToTree(catalog.definition.options.associate {
                        catalog.optionId(workspaceId, it.key).toString() to it.key
                    }))
                    stored.set("wishKeys", mapper.valueToTree(catalog.wishes.associate {
                        catalog.wishId(workspaceId, it.key).toString() to it.key
                    }))
                }
                Files.writeString(outputFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(stored),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                println("Seed analysis model=${result.model} latencyMs=${(System.nanoTime() - started) / 1000000}")
                println("Validation flags=${result.validated.flags.map { it.rule }}")
                println("Option counts=${optionWishCounts(result.validated.output)}")
            }
        }
        context.close()
    }
}

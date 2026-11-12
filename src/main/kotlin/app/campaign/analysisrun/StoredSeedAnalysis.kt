package app.campaign.analysisrun

import app.campaign.model.WishKind
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.Workspace
import app.campaign.port.AnalysisValidationRule
import app.campaign.port.AnalysisResult
import app.campaign.port.AnalysisValidationResult
import app.campaign.rules.analysisInputHash
import app.campaign.workspace.SeedCatalog
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

data class SeedAnalysisFixture(
    val model: String,
    val validated: AnalysisValidationResult.Accepted,
    val inputHash: String,
    val completedAt: Instant,
    val seedWorkspaceId: UUID,
    val optionKeys: Map<UUID, String>,
    val wishKeys: Map<UUID, String>,
) {
    val success: AnalysisResult.Success
        get() = AnalysisResult.Success(model, validated, inputHash, completedAt)
}

@Component
class StoredSeedAnalysis(private val catalog: SeedCatalog, private val mapper: JsonMapper) {
    private val fixtures: Map<String, SeedAnalysisFixture> by lazy {
        catalog.scenarios.associate { scenario ->
            val path = if (scenario.key == "illustrator") "seed" else "seed/${scenario.key}"
            scenario.key to ClassPathResource("$path/analysis-run.json").inputStream.use {
                mapper.readValue(it, SeedAnalysisFixture::class.java)
            }
        }
    }

    fun forWorkspace(workspace: Workspace, options: List<Option>, wishes: List<Wish>): AnalysisResult.Success {
        val scenario = catalog.forOptions(workspace.id, options)
        val stored = fixtures.getValue(scenario.key)
        require(stored.optionKeys.size == 3 && stored.optionKeys.values.toSet() == scenario.definition.options.map { it.key }.toSet())
        require(stored.wishKeys.size == 40 && stored.wishKeys.values.toSet() == scenario.wishes.map { it.key }.toSet())
        validateSeedCoverage(stored)
        val optionIds = stored.optionKeys.mapValues { (_, key) -> catalog.optionId(workspace.id, key) }
        val wishIds = stored.wishKeys.mapValues { (_, key) -> catalog.wishId(workspace.id, key) }
        require(options.map { it.id }.toSet() == optionIds.values.toSet())
        require(wishes.map { it.id }.toSet() == wishIds.values.toSet())
        require(options.all { it.workspaceId == workspace.id } && wishes.all { it.workspaceId == workspace.id })
        val originalOptionIds = optionIds.entries.associate { (original, local) -> local to original }
        val originalWishIds = wishIds.entries.associate { (original, local) -> local to original }
        val originalOptions = options.map { option ->
            Option(
                workspace.id, option.name, option.format, option.fixedAttributes, option.allowedVariants,
                option.unitCost, option.currency, option.minBatch, option.maxCapacity, option.leadTimeText,
                option.shipRegion, originalOptionIds.getValue(option.id),
            )
        }
        val originalWishes = wishes.map { wish ->
            Wish(workspace.id, wish.text, wish.source, wish.displayName, wish.createdAt,
                wish.hidden, originalWishIds.getValue(wish.id))
        }
        // Refuse stale fixtures when a catalog field or the exact source text has changed.
        require(stored.success.inputHash == analysisInputHash(originalOptions, originalWishes)) {
            "Stored analysis does not match the seed catalog"
        }
        val output = stored.success.validated.output
        val mapped = output.copy(
            wishes = output.wishes.map { it.copy(wishId = wishIds.getValue(it.wishId)) },
            verdicts = output.verdicts.map {
                it.copy(wishId = wishIds.getValue(it.wishId), optionId = optionIds.getValue(it.optionId))
            },
            proposal = output.proposal?.let { proposal ->
                proposal.copy(
                    optionId = optionIds.getValue(proposal.optionId),
                    rationale = proposal.rationale.map { it.copy(wishId = wishIds.getValue(it.wishId)) },
                )
            },
            notServed = output.notServed.map { group ->
                group.copy(wishIds = group.wishIds.map(wishIds::getValue))
            },
            questionsForMaker = output.questionsForMaker.map { question ->
                question.copy(wishIds = question.wishIds.map(wishIds::getValue))
            },
            alternatives = output.alternatives.map { it.copy(optionId = optionIds.getValue(it.optionId)) },
            optionIds = output.optionIds.map(optionIds::getValue),
            secondReading = output.secondReading?.let { reading ->
                reading.copy(incompleteWishIds = reading.incompleteWishIds.map(wishIds::getValue),
                    comparisons = reading.comparisons.map {
                        it.copy(wishId = wishIds.getValue(it.wishId), optionId = optionIds.getValue(it.optionId))
                    })
            },
        )
        return stored.success.copy(
            validated = stored.success.validated.copy(output = mapped, flags = stored.success.validated.flags.map { flag ->
                if (flag.rule == AnalysisValidationRule.INCOMPLETE_SECOND_READING) {
                    val original = UUID.fromString(flag.path.removePrefix("wishes[").removeSuffix("]"))
                    flag.copy(path = "wishes[${wishIds.getValue(original)}]")
                } else flag
            }),
            inputHash = analysisInputHash(options, wishes),
        )
    }
}

internal fun validateSeedCoverage(stored: SeedAnalysisFixture) {
    val output = stored.validated.output
    val roster = output.wishes.map { it.wishId }
    require(roster.size == stored.wishKeys.size && roster.toSet() == stored.wishKeys.keys)
    val requests = output.wishes.filter { it.kind == WishKind.REQUEST }.map { it.wishId }.toSet()
    val pairs = output.verdicts.map { it.wishId to it.optionId }
    val expected = requests.flatMap { wish -> stored.optionKeys.keys.map { option -> wish to option } }.toSet()
    require(pairs.size == expected.size && pairs.toSet() == expected)
    require(output.optionIds.size == stored.optionKeys.size && output.optionIds.toSet() == stored.optionKeys.keys)
}

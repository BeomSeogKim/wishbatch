package app.campaign.analysisrun

import app.analysis.AnalysisValidator
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishSource
import app.campaign.model.Workspace
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalysisValidationResult
import app.campaign.port.AnalysisValidationRule
import app.campaign.port.SecondReadingStatus
import app.campaign.rules.analysisInputHash
import app.campaign.workspace.SeedCatalog
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.KotlinModule
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.util.Currency
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScenarioReferenceTests {
    private val catalog = SeedCatalog()
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val stored = StoredSeedAnalysis(catalog, mapper)

    @Test
    fun `coverage gate rejects missing and duplicate wishes or verdicts`() {
        val fixture = ClassPathResource("seed/ceramicist/analysis-run.json").inputStream.use {
            mapper.readValue(it, SeedAnalysisFixture::class.java)
        }
        val output = fixture.validated.output
        fun check(replacement: AnalysisOutput) {
            assertFailsWith<IllegalArgumentException> {
                validateSeedCoverage(fixture.copy(validated = fixture.validated.copy(output = replacement)))
            }
        }
        check(output.copy(wishes = output.wishes.drop(1)))
        check(output.copy(wishes = output.wishes.drop(1) + output.wishes.last()))
        check(output.copy(verdicts = output.verdicts.drop(1)))
        check(output.copy(verdicts = output.verdicts.drop(1) + output.verdicts.last()))
        check(output.copy(optionIds = output.optionIds.drop(1)))
    }

    @Test
    fun `current stored model runs pass the literal validator on their exact snapshots`() {
        val workspace = Workspace(token = "scenario", createdAt = Instant.parse("2026-11-12T00:00:00Z"))
        for (scenario in catalog.scenarios) {
            val options = scenario.definition.options.map { seed ->
                Option(workspace.id, seed.name, seed.format, seed.fixedAttributes, seed.allowedVariants,
                    seed.unitCost, seed.currency, seed.minBatch, seed.maxCapacity, seed.leadTimeText,
                    seed.shipRegion, catalog.optionId(workspace.id, seed.key))
            }
            val wishes = scenario.wishes.map { seed ->
                Wish(workspace.id, seed.text, WishSource.IMPORTED, seed.displayName, workspace.createdAt,
                    id = catalog.wishId(workspace.id, seed.key))
            }
            val result = stored.forWorkspace(workspace, options, wishes)
            val output = result.validated.output
            assertNotEquals("synthetic-reference", result.model)
            val secondReading = assertNotNull(output.secondReading)
            assertEquals(SecondReadingStatus.COMPLETE, secondReading.status)
            assertTrue(secondReading.incompleteWishIds.isEmpty())
            assertNotNull(output.metrics)
            assertLiteralValidation(output, options, wishes)
            assertTrue(scenario.wishes.any { wish -> wish.text.any { it.code > 127 } })
        }
    }

    @Test
    fun `frozen synthetic references retain exact validation and duplicate source coverage`() {
        val manifest = mapper.readTree(Path.of("evaluation/scenarios/manifest.json").toFile())
        for (scenario in manifest.path("scenarios")) {
            val directory = Path.of(scenario.path("sourceDirectory").asString())
            val fixture = mapper.readValue(directory.resolve("analysis-run.json").toFile(), SeedAnalysisFixture::class.java)
            val optionIds = fixture.optionKeys.entries.associate { it.value to it.key }
            val wishIds = fixture.wishKeys.entries.associate { it.value to it.key }
            val options = mapper.readTree(directory.resolve("options.json").toFile()).path("options").toList().map { seed ->
                val node = seed.deepCopy() as ObjectNode
                val key = seed.path("key").asString()
                node.remove("key")
                node.put("id", optionIds.getValue(key).toString())
                node.put("workspaceId", fixture.seedWorkspaceId.toString())
                mapper.treeToValue(node, Option::class.java)
            }
            val wishes = mapper.readTree(directory.resolve("wishes.json").toFile()).toList().map { seed ->
                Wish(fixture.seedWorkspaceId, seed.path("text").asString(), WishSource.IMPORTED,
                    seed.path("displayName").asString(), Instant.EPOCH, id = wishIds.getValue(seed.path("key").asString()))
            }
            assertEquals("synthetic-reference", fixture.model)
            assertNull(fixture.validated.output.secondReading)
            assertEquals(fixture.inputHash, analysisInputHash(options, wishes))
            validateSeedCoverage(fixture)
            assertLiteralValidation(fixture.validated.output, options, wishes)
            assertTrue(wishes.map { it.text }.distinct().size < wishes.size)
        }
    }

    private fun assertLiteralValidation(output: AnalysisOutput, options: List<Option>, wishes: List<Wish>) {
        val raw = mapOf(
            "wishes" to output.wishes.map { wish -> mapOf(
                "wish_id" to wish.wishId, "kind" to wish.kind,
                "needs" to wish.needs.map { need -> mapOf("strength" to need.strength,
                    "attribute" to need.attribute, "value" to need.value, "quote" to need.quote) },
                "max_unit_price" to wish.maxUnitPrice?.let {
                    BigDecimal.valueOf(it.minorUnits, Currency.getInstance(it.currency).defaultFractionDigits)
                }, "quantity" to wish.quantity, "region" to wish.region,
            ) },
            "verdicts" to output.verdicts.map { mapOf("wish_id" to it.wishId, "option_id" to it.optionId,
                "verdict" to it.verdict, "reason" to it.reason) },
            "proposal" to output.proposal?.let { proposal -> mapOf("option_id" to proposal.optionId,
                "suggested_variants" to proposal.suggestedVariants, "rationale" to proposal.rationale.map {
                    mapOf("text" to it.text, "wish_id" to it.wishId, "quote" to it.quote)
                }) },
            "no_proposal_reason" to output.noProposalReason,
            "not_served" to output.notServed.map { mapOf("label" to it.label, "wish_ids" to it.wishIds, "reason" to it.reason) },
            "questions_for_maker" to output.questionsForMaker.map { mapOf("text" to it.text, "wish_ids" to it.wishIds) },
            "alternatives" to output.alternatives.map { mapOf("option_id" to it.optionId, "why_not_first" to it.whyNotFirst) },
        )
        val validated = assertIs<AnalysisValidationResult.Accepted>(
            AnalysisValidator(mapper).validate(mapper.writeValueAsString(raw), options, wishes),
        )
        assertTrue(validated.flags.none { it.rule != AnalysisValidationRule.V6 }, "${validated.flags}")
        assertEquals(output.wishes, validated.output.wishes)
        assertEquals(output.verdicts, validated.output.verdicts)
        assertEquals(output.proposal, validated.output.proposal)
    }
}

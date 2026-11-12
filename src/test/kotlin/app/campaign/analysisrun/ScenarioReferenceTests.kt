package app.campaign.analysisrun

import app.analysis.AnalysisValidator
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishSource
import app.campaign.model.Workspace
import app.campaign.port.AnalysisValidationResult
import app.campaign.port.AnalysisValidationRule
import app.campaign.workspace.SeedCatalog
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
        fun check(replacement: app.campaign.port.AnalysisOutput) {
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
    fun `every stored reference passes the analysis validator on its exact snapshot`() {
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
            val output = stored.forWorkspace(workspace, options, wishes).validated.output
            val raw = mapOf(
                "wishes" to output.wishes.map { wish -> mapOf(
                    "wish_id" to wish.wishId, "kind" to wish.kind,
                    "needs" to wish.needs.map { need -> mapOf("strength" to need.strength,
                        "attribute" to need.attribute, "value" to need.value, "quote" to need.quote) },
                    "max_unit_price" to wish.maxUnitPrice?.let { null }, "quantity" to wish.quantity, "region" to wish.region,
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
            assertTrue(validated.flags.none { it.rule != AnalysisValidationRule.V6 }, "${scenario.key}: ${validated.flags}")
            assertEquals(output.wishes.size, validated.output.wishes.size)
            assertEquals(output.verdicts, validated.output.verdicts)
            assertEquals(output.proposal, validated.output.proposal)
            assertTrue(scenario.wishes.any { wish -> wish.text.any { it.code > 127 } })
            assertTrue(scenario.wishes.map { it.text }.distinct().size < scenario.wishes.size)
        }
    }
}

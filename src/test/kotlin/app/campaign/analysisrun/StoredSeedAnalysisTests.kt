package app.campaign.analysisrun

import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.model.WishSource
import app.campaign.model.Workspace
import app.campaign.rules.analysisInputHash
import app.campaign.workspace.SeedCatalog
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StoredSeedAnalysisTests {
    private val catalog = SeedCatalog()
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val stored = StoredSeedAnalysis(catalog, mapper)
    private val now = Instant.parse("2026-11-12T12:00:00Z")

    @Test
    fun `every nested seed reference maps into an isolated workspace while provenance stays intact`() {
        val first = workspace("first")
        val second = workspace("second")
        val firstRun = stored.forWorkspace(first, options(first), wishes(first))
        val secondRun = stored.forWorkspace(second, options(second), wishes(second))
        val fixture = ClassPathResource("seed/analysis-run.json").inputStream.use {
            mapper.readValue(it, SeedAnalysisFixture::class.java)
        }
        for ((workspace, result) in listOf(first to firstRun, second to secondRun)) {
            val output = result.validated.output
            val optionIds = options(workspace).map { it.id }.toSet()
            val wishIds = wishes(workspace).map { it.id }.toSet()
            assertEquals(optionIds, output.optionIds.toSet())
            assertEquals(wishIds, output.wishes.map { it.wishId }.toSet())
            assertTrue(output.verdicts.all { it.wishId in wishIds && it.optionId in optionIds })
            assertTrue(output.proposal!!.optionId in optionIds)
            assertTrue(output.proposal!!.rationale.all { it.wishId in wishIds })
            assertTrue(output.notServed.flatMap { it.wishIds }.all { it in wishIds })
            assertTrue(output.questionsForMaker.flatMap { it.wishIds }.all { it in wishIds })
            assertTrue(output.alternatives.all { it.optionId in optionIds })
            assertEquals(analysisInputHash(options(workspace), wishes(workspace)), result.inputHash)
            assertEquals(fixture.success.model, result.model)
            assertEquals(fixture.success.completedAt, result.completedAt)
            assertEquals(fixture.success.validated.flags, result.validated.flags)
            assertEquals(fixture.success.validated.output.proposal!!.rationale.map { it.text to it.quote },
                output.proposal!!.rationale.map { it.text to it.quote })
        }
        assertNotEquals(firstRun.inputHash, secondRun.inputHash)
        assertTrue(firstRun.validated.output.wishes.map { it.wishId }.toSet()
            .intersect(secondRun.validated.output.wishes.map { it.wishId }.toSet()).isEmpty())
        assertTrue(firstRun.model.isNotBlank())
        assertTrue(firstRun.completedAt.isAfter(Instant.parse("2026-01-01T00:00:00Z")))
    }

    @Test
    fun `stored proposal serves postcards excludes unsupported formats and ignores instruction injections`() {
        val workspace = workspace("review")
        val inputWishes = wishes(workspace)
        val output = stored.forWorkspace(workspace, options(workspace), inputWishes).validated.output
        val postcardId = catalog.optionId(workspace.id, "postcard-set")
        assertEquals(postcardId, output.proposal!!.optionId)
        for (key in listOf("w30", "w31", "w32")) {
            assertEquals(CompatibilityVerdict.INCOMPATIBLE, output.verdicts.single {
                it.wishId == catalog.wishId(workspace.id, key) && it.optionId == postcardId
            }.verdict)
        }
        for (key in listOf("w39", "w40")) {
            val id = catalog.wishId(workspace.id, key)
            assertEquals(WishKind.NOT_A_REQUEST, output.wishes.single { it.wishId == id }.kind)
            assertTrue(output.verdicts.none { it.wishId == id })
        }
        val sources = inputWishes.associateBy { it.id }
        for (wish in output.wishes) {
            for (need in wish.needs) {
                assertTrue(normalize(sources.getValue(wish.wishId).text).contains(normalize(need.quote)))
            }
        }
        for (evidence in output.proposal!!.rationale) {
            assertTrue(normalize(sources.getValue(evidence.wishId).text).contains(normalize(evidence.quote)))
        }
    }

    @Test
    fun `changed seed text cannot silently reuse the stored result`() {
        val workspace = workspace("changed")
        val wishes = wishes(workspace).toMutableList()
        val first = wishes.first()
        wishes[0] = Wish(first.workspaceId, "A completely different request", first.source,
            first.displayName, first.createdAt, first.hidden, first.id)
        assertFailsWith<IllegalArgumentException> { stored.forWorkspace(workspace, options(workspace), wishes) }
    }

    private fun workspace(token: String) = Workspace(token = token, createdAt = now)

    private fun options(workspace: Workspace) = catalog.definition.options.map { seed ->
        Option(workspace.id, seed.name, seed.format, seed.fixedAttributes, seed.allowedVariants,
            seed.unitCost, seed.currency, seed.minBatch, seed.maxCapacity, seed.leadTimeText,
            seed.shipRegion, catalog.optionId(workspace.id, seed.key))
    }

    private fun wishes(workspace: Workspace) = catalog.wishes.map { seed ->
        Wish(workspace.id, seed.text, WishSource.IMPORTED, seed.displayName, now,
            id = catalog.wishId(workspace.id, seed.key))
    }

    private fun normalize(value: String) = value.replace(Regex("(?U)\\s+"), " ").trim()
}

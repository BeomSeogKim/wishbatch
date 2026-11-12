package app.analysis

import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.workspace.SeedCatalog
import app.campaign.rules.analysisInputHash
import app.campaign.model.WishSource
import app.campaign.port.AnalysisFailure
import app.campaign.port.AnalysisResult
import app.campaign.port.AnalysisValidationResult
import app.campaign.port.WishAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.context.support.GenericApplicationContext
import org.springframework.mock.env.MockEnvironment
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeedRunGeneratorTests {
    @TempDir
    lateinit var directory: Path
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val workspaceId = UUID.randomUUID()
    private val option = Option(workspaceId, "Card", "postcard", emptyMap(), emptyMap(), 100, "USD", 5, 20, "Two weeks", "US")
    private val wish = Wish(workspaceId, "Hello!", WishSource.ONSITE, createdAt = now)

    @Test
    fun `seed runner reads snapshots and writes validated metadata with a fake analyzer`() {
        val environment = files()
        val accepted = AnalysisValidator(mapper).validate("""
            {"wishes":[{"wish_id":"${wish.id}","kind":"NOT_A_REQUEST","needs":[]}],
            "verdicts":[],"proposal":null,"no_proposal_reason":"No request.",
            "not_served":[],"questions_for_maker":[],"alternatives":[]}
        """.trimIndent(), listOf(option), listOf(wish)) as AnalysisValidationResult.Accepted
        val analyzer = WishAnalyzer { options, wishes ->
            assertEquals(option.id, options.single().id)
            assertEquals(wish.text, wishes.single().text)
            AnalysisResult.Success("fake-model", accepted, "test-hash", now)
        }
        GenericApplicationContext().use { context ->
            context.refresh()
            SeedRunGenerator(analyzer, mapper, environment, context).run(DefaultApplicationArguments())
            assertFalse(context.isActive)
        }
        val result = mapper.readTree(directory.resolve("output.json").toFile())
        assertEquals("fake-model", result["model"].asString())
        assertEquals("test-hash", result["inputHash"].asString())
        assertEquals(now.toString(), result["completedAt"].asString())
    }

    @Test
    fun `existing output prevents any call and failure writes no output`() {
        val environment = files()
        GenericApplicationContext().use { context ->
            val failing = SeedRunGenerator(WishAnalyzer { _, _ -> AnalysisResult.Failure(AnalysisFailure.NOT_CONFIGURED) }, mapper, environment, context)
            assertFailsWith<IllegalStateException> { failing.run(DefaultApplicationArguments()) }
            assertFalse(Files.exists(directory.resolve("output.json")))
            Files.writeString(directory.resolve("output.json"), "existing")
            val unused = SeedRunGenerator(WishAnalyzer { _, _ -> error("Must not call analyzer") }, mapper, environment, context)
            assertFailsWith<IllegalStateException> { unused.run(DefaultApplicationArguments()) }
            assertEquals("existing", Files.readString(directory.resolve("output.json")))
        }
    }

    @Test
    fun `default seed uses canonical keys stable IDs and all forty wishes`() {
        val catalog = SeedCatalog()
        val seedWorkspace = UUID(0, 0)
        var expectedHash = ""
        val analyzer = WishAnalyzer { options, wishes ->
            assertEquals(3, options.size)
            assertEquals(40, wishes.size)
            assertEquals(catalog.definition.options.map { catalog.optionId(seedWorkspace, it.key) }, options.map { it.id })
            assertEquals(catalog.wishes.map { catalog.wishId(seedWorkspace, it.key) }, wishes.map { it.id })
            assertEquals(catalog.wishes.map { it.text }, wishes.map { it.text })
            assertTrue(wishes.all { it.source == WishSource.IMPORTED && it.createdAt == Instant.EPOCH })
            assertTrue(options.all { it.workspaceId == seedWorkspace })
            assertTrue(wishes.all { it.workspaceId == seedWorkspace })
            expectedHash = analysisInputHash(options, wishes)
            fakeSuccess(options, wishes)
        }
        repeat(2) { run ->
            val output = directory.resolve("canonical-$run.json")
            val environment = MockEnvironment().withProperty("LLM_SEED_OUTPUT_FILE", output.toString())
            GenericApplicationContext().use { context ->
                context.refresh()
                SeedRunGenerator(analyzer, mapper, environment, context).run(DefaultApplicationArguments())
                assertFalse(context.isActive)
            }
            val stored = mapper.readTree(output.toFile())
            assertEquals(seedWorkspace.toString(), stored["seedWorkspaceId"].asString())
            assertEquals("fake-model", stored["model"].asString())
            assertEquals(now.toString(), stored["completedAt"].asString())
            assertEquals(expectedHash, stored["inputHash"].asString())
            assertEquals(40, stored["validated"]["output"]["wishes"].size())
            val optionKeys = stored["optionKeys"].properties().associate { it.key to it.value.asString() }
            val wishKeys = stored["wishKeys"].properties().associate { it.key to it.value.asString() }
            assertEquals(catalog.definition.options.associate { catalog.optionId(seedWorkspace, it.key).toString() to it.key }, optionKeys)
            assertEquals(catalog.wishes.associate { catalog.wishId(seedWorkspace, it.key).toString() to it.key }, wishKeys)
        }
        assertEquals(Files.readString(directory.resolve("canonical-0.json")), Files.readString(directory.resolve("canonical-1.json")))
    }

    @Test
    fun `partial seed analysis cannot be written as a complete stored run`() {
        val output = directory.resolve("partial.json")
        val environment = MockEnvironment().withProperty("LLM_SEED_OUTPUT_FILE", output.toString())
        val analyzer = WishAnalyzer { options, wishes -> fakeSuccess(options, wishes.dropLast(1)) }
        GenericApplicationContext().use { context ->
            val generator = SeedRunGenerator(analyzer, mapper, environment, context)
            val failure = assertFailsWith<IllegalStateException> { generator.run(DefaultApplicationArguments()) }
            assertEquals("Stored seed must contain every wish", failure.message)
        }
        assertFalse(Files.exists(output))
    }

    @Test
    fun `one snapshot path without the other is rejected before analysis`() {
        for (key in listOf("LLM_SEED_OPTIONS_FILE", "LLM_SEED_WISHES_FILE")) {
            val output = directory.resolve("mismatched.json")
            val environment = MockEnvironment().withProperty("LLM_SEED_OUTPUT_FILE", output.toString())
                .withProperty(key, directory.resolve("missing-input.json").toString())
            GenericApplicationContext().use { context ->
                val generator = SeedRunGenerator(WishAnalyzer { _, _ -> error("Must not call analyzer") }, mapper, environment, context)
                assertFailsWith<IllegalArgumentException> { generator.run(DefaultApplicationArguments()) }
            }
            assertFalse(Files.exists(output))
        }
    }

    private fun fakeSuccess(options: List<Option>, wishes: List<Wish>): AnalysisResult.Success {
        val classifications = wishes.map { mapOf("wish_id" to it.id, "kind" to "NOT_A_REQUEST", "needs" to emptyList<Any>()) }
        val json = mapper.writeValueAsString(mapOf(
            "wishes" to classifications, "verdicts" to emptyList<Any>(), "proposal" to null,
            "no_proposal_reason" to "Synthetic test classification.", "not_served" to emptyList<Any>(),
            "questions_for_maker" to emptyList<Any>(), "alternatives" to emptyList<Any>(),
        ))
        val accepted = AnalysisValidator(mapper).validate(json, options, wishes) as AnalysisValidationResult.Accepted
        return AnalysisResult.Success("fake-model", accepted, analysisInputHash(options, wishes), now)
    }

    private fun files(): MockEnvironment {
        mapper.writeValue(directory.resolve("options.json").toFile(), listOf(option))
        mapper.writeValue(directory.resolve("wishes.json").toFile(), listOf(wish))
        return MockEnvironment()
            .withProperty("LLM_SEED_OPTIONS_FILE", directory.resolve("options.json").toString())
            .withProperty("LLM_SEED_WISHES_FILE", directory.resolve("wishes.json").toString())
            .withProperty("LLM_SEED_OUTPUT_FILE", directory.resolve("output.json").toString())
    }
}

package app.analysis

import app.campaign.model.Option
import app.campaign.model.Wish
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

    private fun files(): MockEnvironment {
        mapper.writeValue(directory.resolve("options.json").toFile(), listOf(option))
        mapper.writeValue(directory.resolve("wishes.json").toFile(), listOf(wish))
        return MockEnvironment()
            .withProperty("LLM_SEED_OPTIONS_FILE", directory.resolve("options.json").toString())
            .withProperty("LLM_SEED_WISHES_FILE", directory.resolve("wishes.json").toString())
            .withProperty("LLM_SEED_OUTPUT_FILE", directory.resolve("output.json").toString())
    }
}

package app.analysis

import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.rules.analysisInputHash
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Path
import kotlin.test.assertEquals

class ScenarioEvaluationExportTests {
    @TempDir
    lateinit var directory: Path
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Test
    fun `offline exports deserialize as generator inputs and match application hashes`() {
        val manifest = mapper.readTree(Path.of("evaluation/scenarios/manifest.json").toFile())
        for (scenario in manifest["scenarios"]) {
            val key = scenario["key"].asString()
            val destination = directory.resolve(key)
            val process = ProcessBuilder("python3", "evaluation/export_scenario.py", key, destination.toString())
                .redirectErrorStream(true).start()
            val diagnostic = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.waitFor(), diagnostic)
            val options = mapper.readValue(destination.resolve("options.json").toFile(), object : TypeReference<List<Option>>() {})
            val wishes = mapper.readValue(destination.resolve("wishes.json").toFile(), object : TypeReference<List<Wish>>() {})
            assertEquals(3, options.size)
            assertEquals(40, wishes.size)
            assertEquals(scenario["inputHash"].asString(), analysisInputHash(options, wishes))
        }
    }
}

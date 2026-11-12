package app.analysis

import org.springframework.core.env.Environment

class AnalysisProperties(
    val apiKey: String = "",
    val model: String = "gpt-6.1-sol",
    val dailyRunCap: Int = 100,
    val reasoningEffort: String = "low",
    val batchSize: Int = 4,
    val concurrency: Int = 10,
) {
    init {
        require(reasoningEffort in setOf("none", "minimal", "low", "medium", "high"))
        require(batchSize in 1..10)
        require(concurrency in 1..16)
        require(model.isNotBlank()) { "LLM_MODEL must not be blank" }
        require(dailyRunCap >= 0) { "LLM_DAILY_RUN_CAP must be nonnegative" }
    }

    companion object {
        fun from(environment: Environment) = AnalysisProperties(
            apiKey = environment.getProperty("LLM_API_KEY", ""),
            model = environment.getProperty("LLM_MODEL", "gpt-6.1-sol"),
            reasoningEffort = environment.getProperty("LLM_REASONING_EFFORT", "low"),
            batchSize = environment.getProperty("LLM_BATCH_SIZE", Int::class.java, 4),
            concurrency = environment.getProperty("LLM_CONCURRENCY", Int::class.java, 10),
            dailyRunCap = environment.getProperty("LLM_DAILY_RUN_CAP", Int::class.java, 100),
        )
    }
}

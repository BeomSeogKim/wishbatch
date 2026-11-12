package app.analysis

import org.springframework.core.env.Environment

class AnalysisProperties(
    val apiKey: String = "",
    val model: String = "gpt-6.1-sol",
    val dailyRunCap: Int = 10,
) {
    init {
        require(model.isNotBlank()) { "LLM_MODEL must not be blank" }
        require(dailyRunCap >= 0) { "LLM_DAILY_RUN_CAP must be nonnegative" }
    }

    companion object {
        fun from(environment: Environment) = AnalysisProperties(
            apiKey = environment.getProperty("LLM_API_KEY", ""),
            model = environment.getProperty("LLM_MODEL", "gpt-6.1-sol"),
            dailyRunCap = environment.getProperty("LLM_DAILY_RUN_CAP", Int::class.java, 10),
        )
    }
}

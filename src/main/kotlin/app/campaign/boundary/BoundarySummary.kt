package app.campaign.boundary

import app.campaign.analysisrun.StoredAnalysisRun
import app.campaign.port.AnalysisValidationRule
import java.time.Instant
import java.util.UUID

data class BoundaryFlagGroup(val label: String, val meaning: String, val count: Int)

data class BoundarySummary(
    val runId: UUID,
    val model: String,
    val time: Instant,
    val inputHash: String,
    val seed: Boolean,
    val representedWishCount: Int,
    val newWishCount: Int,
    val flags: List<BoundaryFlagGroup>,
    val overrideCount: Int,
)

fun summarizeBoundary(stored: StoredAnalysisRun?): BoundarySummary? {
    if (stored == null) return null
    val groups = stored.flags.groupingBy { it.rule }.eachCount()
    return BoundarySummary(
        runId = stored.run.id,
        model = stored.run.model,
        time = stored.run.createdAt,
        inputHash = stored.run.inputHash,
        seed = stored.run.isSeed,
        representedWishCount = stored.originalOutput.wishes.map { it.wishId }.distinct().size,
        newWishCount = stored.newWishCount,
        flags = AnalysisValidationRule.entries.mapNotNull { rule ->
            groups[rule]?.let { count ->
                val (label, meaning) = when (rule) {
                    AnalysisValidationRule.V1 -> "Unknown reference (V1)" to "An unknown wish or option reference was removed."
                    AnalysisValidationRule.V2 -> "Ungrounded quote (V2)" to "A quote could not be matched to its source."
                    AnalysisValidationRule.V3 -> "Ignored commercial field (V3)" to "A model-supplied commercial field was ignored."
                    AnalysisValidationRule.V4 -> "Unsupported variant (V4)" to "A variant was outside the allowed options."
                    AnalysisValidationRule.V5 -> "Multiple proposals (V5)" to "Multiple proposals reject an output."
                    AnalysisValidationRule.V6 -> "Regrouping (V6)" to "Not-served groups were rebuilt from verdicts."
                    AnalysisValidationRule.MODEL_WRITTEN_NUMBER -> "Numeric rationale" to "A numeric rationale was not supported by catalog attributes."
                    AnalysisValidationRule.UNANALYZED_WISH -> "Omitted wish" to "A wish could not be analyzed twice."
                }
                BoundaryFlagGroup(label, meaning, count)
            }
        },
        overrideCount = stored.overrides.size,
    )
}

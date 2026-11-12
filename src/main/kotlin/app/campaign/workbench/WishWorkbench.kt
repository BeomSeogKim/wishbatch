package app.campaign.workbench

import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalysisValidationFlag
import app.campaign.review.WishReviewOverride
import java.util.UUID

data class WorkbenchLabel(val optionId: UUID, val optionName: String, val label: String, val reason: String) {
    val displayLabel: String get() = when (label) {
        "COMPATIBLE" -> "Compatible"
        "CONDITIONAL" -> "Depends"
        "INCOMPATIBLE" -> "Not served"
        "NOT_A_REQUEST" -> "Not a request"
        else -> "Unanalyzed"
    }
}

data class WorkbenchRow(
    val id: UUID,
    val text: String,
    val labels: List<WorkbenchLabel>,
    val evidence: List<String>,
    val flags: List<String>,
    val source: String,
    val language: String,
    val hidden: Boolean,
    val provenance: List<String>,
)

data class WorkbenchFilter(
    val optionId: UUID? = null,
    val label: String = "",
    val q: String = "",
    val sort: String = "id",
) {
    fun matches(row: WorkbenchRow): Boolean {
        val matchingLabels = row.labels.filter { optionId == null || it.optionId == optionId }
        if (optionId != null && matchingLabels.isEmpty()) return false
        if (label.isNotEmpty() && matchingLabels.none { it.label == label }) return false
        val text = listOf(row.id.toString(), row.text, row.source, row.language,
            if (row.hidden) "Hidden" else "Visible", row.labels.joinToString(" ") {
            "${it.optionName} ${it.label} ${it.displayLabel} ${it.reason}"
        }, row.evidence.joinToString(" "), row.flags.joinToString(" "), row.provenance.joinToString(" "))
            .joinToString(" ")
        return text.contains(q.trim(), ignoreCase = true)
    }

    fun sorted(rows: List<WorkbenchRow>): List<WorkbenchRow> = when (sort) {
        "text" -> rows.sortedBy { it.text.lowercase() }
        "source" -> rows.sortedBy { it.source }
        "language" -> rows.sortedBy { it.language }
        "label" -> rows.sortedBy { row -> row.labels.firstOrNull { optionId == null || it.optionId == optionId }?.label }
        else -> rows.sortedBy { it.id }
    }
}

/** A source wish has one row regardless of repeated analysis entries or extracted quantities. */
fun wishWorkbenchRows(
    wishes: List<Wish>,
    options: List<Option>,
    output: AnalysisOutput?,
    flags: List<AnalysisValidationFlag>,
    overrides: List<WishReviewOverride> = emptyList(),
    originalOutput: AnalysisOutput? = output,
): List<WorkbenchRow> {
    val analyzed = output?.wishes.orEmpty().associateBy { it.wishId }
    val verdicts = output?.verdicts.orEmpty().associateBy { it.wishId to it.optionId }
    return wishes.distinctBy { it.id }.map { wish ->
        val result = analyzed[wish.id]
        val quotes = result?.needs.orEmpty().map { it.quote } + output?.proposal?.rationale.orEmpty()
            .filter { it.wishId == wish.id }.map { it.quote }
        val rowFlags = flags.filter { flag ->
            flag.path == "wishes[${wish.id}]" || flag.path.startsWith("wishes[${wish.id}].")
        }.map { "${it.rule}: ${it.message}" }
        val decisions = overrides.filter { it.wishId == wish.id }.sortedBy { it.createdAt }
        val readingFlags = originalOutput?.secondReading?.comparisons.orEmpty()
            .filter { it.wishId == wish.id && it.needsYourEyes }.map { comparison ->
                val optionName = options.firstOrNull { it.id == comparison.optionId }?.name ?: "Unknown option"
                val status = if (decisions.any { it.optionId == comparison.optionId }) "Review recorded" else "Needs your eyes"
                "$status: $optionName. First reading: ${comparison.firstKind} / ${comparison.firstVerdict ?: "Unclassified"}; " +
                    "second reading: ${comparison.secondKind} / ${comparison.secondVerdict ?: "Unclassified"}."
            }
        WorkbenchRow(
            id = wish.id,
            text = wish.text,
            labels = options.distinctBy { it.id }.map { option ->
                val verdict = verdicts[wish.id to option.id]
                WorkbenchLabel(option.id, option.name, when {
                    result?.kind == WishKind.NOT_A_REQUEST -> "NOT_A_REQUEST"
                    verdict != null -> verdict.verdict.name
                    else -> "UNANALYZED"
                }, verdict?.reason.orEmpty())
            },
            evidence = quotes.distinct().filter { it.isNotBlank() && normalized(wish.text).contains(normalized(it)) },
            flags = rowFlags + readingFlags,
            source = wish.source.name,
            language = "Unknown",
            hidden = wish.hidden,
            provenance = decisions.map { decision ->
                val optionName = options.firstOrNull { it.id == decision.optionId }?.name ?: "Unknown option"
                "${decision.source} / $optionName / ${decision.verdict} / revision ${decision.revision} / " +
                    "${decision.createdAt} / reason: ${decision.reason} / run ${decision.runId}"
            },
        )
    }
}

fun workbenchCounts(rows: List<WorkbenchRow>, optionId: UUID?): Map<String, Int> =
    rows.distinctBy { it.id }.flatMap { row ->
        row.labels.filter { optionId == null || it.optionId == optionId }.map { it.label }.distinct()
    }.groupingBy { it }.eachCount()

private fun normalized(value: String): String = value.replace(Regex("[\\s\\p{Z}]+"), " ").trim()

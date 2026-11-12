package app.campaign.workbench

import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalysisValidationFlag
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
): List<WorkbenchRow> {
    val analyzed = output?.wishes.orEmpty().associateBy { it.wishId }
    val verdicts = output?.verdicts.orEmpty().associateBy { it.wishId to it.optionId }
    return wishes.distinctBy { it.id }.map { wish ->
        val result = analyzed[wish.id]
        val quotes = result?.needs.orEmpty().map { it.quote } + output?.proposal?.rationale.orEmpty()
            .filter { it.wishId == wish.id }.map { it.quote }
        val rowFlags = flags.filter { flag ->
            flag.path.contains(wish.id.toString()) ||
                output?.wishes?.withIndex()?.any { (index, item) ->
                    item.wishId == wish.id && indexedPath(flag.path, "wishes", index)
                } == true || output?.verdicts?.withIndex()?.any { (index, item) ->
                    item.wishId == wish.id && indexedPath(flag.path, "verdicts", index)
                } == true
        }.map { "${it.rule}: ${it.message}" }
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
            flags = rowFlags,
            source = wish.source.name,
            language = "Unknown",
            hidden = wish.hidden,
            provenance = emptyList(),
        )
    }
}

fun workbenchCounts(rows: List<WorkbenchRow>, optionId: UUID?): Map<String, Int> =
    rows.distinctBy { it.id }.flatMap { row ->
        row.labels.filter { optionId == null || it.optionId == optionId }.map { it.label }.distinct()
    }.groupingBy { it }.eachCount()

private fun indexedPath(path: String, collection: String, index: Int): Boolean =
    path == "$collection[$index]" || path.startsWith("$collection[$index].")

private fun normalized(value: String): String = value.replace(Regex("[\\s\\p{Z}]+"), " ").trim()

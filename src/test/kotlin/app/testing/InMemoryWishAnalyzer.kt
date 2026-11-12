package app.testing

import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.port.AnalysisResult
import app.campaign.port.WishAnalyzer

data class AnalysisCall(val options: List<Option>, val wishes: List<Wish>)

/** Queue validated successes or failures in results; every input is snapshotted before returning. */
class InMemoryWishAnalyzer : WishAnalyzer {
    val results = ArrayDeque<AnalysisResult>()
    private val recordedCalls = mutableListOf<AnalysisCall>()
    val calls: List<AnalysisCall> get() = recordedCalls.toList()

    override fun analyze(options: List<Option>, wishes: List<Wish>): AnalysisResult {
        recordedCalls.add(AnalysisCall(options.map { it.snapshot() }, wishes.map { it.snapshot() }))
        return results.removeFirstOrNull() ?: throw AssertionError("No scripted wish analysis result")
    }

    private fun Option.snapshot() = Option(
        workspaceId = workspaceId,
        name = name,
        format = format,
        fixedAttributes = fixedAttributes.toMap(),
        allowedVariants = allowedVariants.mapValues { (_, values) -> values.toList() },
        unitCost = unitCost,
        currency = currency,
        minBatch = minBatch,
        maxCapacity = maxCapacity,
        leadTimeText = leadTimeText,
        shipRegion = shipRegion,
        id = id,
    )

    private fun Wish.snapshot() = Wish(
        workspaceId = workspaceId,
        text = text,
        source = source,
        displayName = displayName,
        createdAt = createdAt,
        hidden = hidden,
        id = id,
    )
}

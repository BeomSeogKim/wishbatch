package app.campaign.port

import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Money
import app.campaign.model.NeedAttribute
import app.campaign.model.NeedStrength
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishKind
import java.time.Instant
import java.util.UUID

data class WishNeed(val strength: NeedStrength, val attribute: NeedAttribute, val value: String, val quote: String)

/** Extracted fan limits are evidence, never authoritative offer prices or production quantities. */
data class AnalyzedWish(
    val wishId: UUID,
    val kind: WishKind,
    val needs: List<WishNeed>,
    val maxUnitPrice: Money? = null,
    val quantity: Int? = null,
    val region: String? = null,
)

/** A validated REQUEST has one verdict per input option; reason is at most 140 characters. */
data class WishVerdict(
    val wishId: UUID,
    val optionId: UUID,
    val verdict: CompatibilityVerdict,
    val reason: String,
)

data class ProposalEvidence(val text: String, val wishId: UUID, val quote: String)

data class Proposal(
    val optionId: UUID,
    val suggestedVariants: Map<String, String>,
    val rationale: List<ProposalEvidence>,
)

data class NotServedGroup(val label: String, val wishIds: List<UUID>, val reason: String)
data class MakerQuestion(val text: String, val wishIds: List<UUID>)
data class AnalysisAlternative(val optionId: UUID, val whyNotFirst: String)

data class WishReadingComparison(
    val wishId: UUID,
    val optionId: UUID,
    val firstKind: WishKind,
    val secondKind: WishKind,
    val firstVerdict: CompatibilityVerdict?,
    val secondVerdict: CompatibilityVerdict?,
) {
    val needsYourEyes: Boolean get() = firstKind != secondKind || firstVerdict != secondVerdict
}

/** Agreement counts wishes whose kind and verdicts agree across every option. */
data class SecondReading(
    val agreedWishCount: Int,
    val comparedWishCount: Int,
    val comparisons: List<WishReadingComparison>,
    val batchCompositionChanged: Boolean = false,
)

/**
 * Normalized output: at most one proposal, zero to two alternatives, and no model-owned offer
 * prices, thresholds, capacities or dates. A missing proposal requires noProposalReason.
 * Plain data serializable with the application's JSON mapper; AnalysisRun.resultJson stores
 * this shape. optionIds contains every input option ID, supplied by the adapter, not the model.
 * Adapters convert decimal price limits into exact minor units in the input currency.
 */
data class AnalysisOutput(
    val wishes: List<AnalyzedWish>,
    val verdicts: List<WishVerdict>,
    val proposal: Proposal?,
    val noProposalReason: String?,
    val notServed: List<NotServedGroup>,
    val questionsForMaker: List<MakerQuestion>,
    val alternatives: List<AnalysisAlternative>,
    val optionIds: List<UUID>,
    val secondReading: SecondReading? = null,
)

enum class AnalysisValidationRule {
    /** Unknown wish/option references are dropped. */
    V1,
    /** Quotes not present after whitespace normalization are dropped. */
    V2,
    /** Model-supplied commercial terms are ignored. */
    V3,
    /** Variants outside the option's allowed values are dropped. */
    V4,
    /** Multiple proposals reject the entire output. */
    V5,
    /** Not-served groups are rebuilt from the proposed option's verdicts. */
    V6,
    /** Numeric rationale is dropped unless it names the selected option's catalog attributes. */
    MODEL_WRITTEN_NUMBER,
    /** An exhausted classification batch omits this wish; path is wishes[UUID]. */
    UNANALYZED_WISH,
}

/** Path identifies an output location; message must not contain raw private input. */
data class AnalysisValidationFlag(val rule: AnalysisValidationRule, val path: String, val message: String)

sealed interface AnalysisValidationResult {
    val flags: List<AnalysisValidationFlag>

    data class Accepted(val output: AnalysisOutput, override val flags: List<AnalysisValidationFlag>) : AnalysisValidationResult
    data class Rejected(override val flags: List<AnalysisValidationFlag>) : AnalysisValidationResult
}

enum class AnalysisFailure {
    /** No provider key is configured. */
    NOT_CONFIGURED,
    /** The analysis time budget was exceeded. */
    TIMEOUT,
    INVALID_JSON, INVALID_OUTPUT, PROVIDER_FAILURE, RUN_LIMIT_REACHED,
}

sealed interface AnalysisResult {
    data class Success(
        val model: String,
        val validated: AnalysisValidationResult.Accepted,
        val inputHash: String,
        val completedAt: Instant,
    ) : AnalysisResult
    data class Failure(val reason: AnalysisFailure, val flags: List<AnalysisValidationFlag> = emptyList()) : AnalysisResult
}

/** Events describe completed work; repeated readings can contain the same wish IDs. */
data class AnalysisProgressEvent(
    val stage: String,
    val reading: Int? = null,
    val batch: Int? = null,
    val totalBatches: Int? = null,
    val processedWishIds: List<UUID> = emptyList(),
    val flags: List<AnalysisValidationFlag> = emptyList(),
)

fun interface AnalysisObserver {
    fun onProgress(event: AnalysisProgressEvent)
}

/**
 * Callers select the wishes to include before analysis. Read only the fields covered by
 * app.campaign.rules.analysisInputHash; callers and implementations use that shared function.
 * Success reports that hash and the completion instant from an injected Clock.
 * Analyze only the supplied snapshot. Success contains validated output; failures preserve the
 * caller's last valid run. Unknown fields are discarded. Invalid JSON or multiple proposals may
 * be retried once only. Implementations enforce their configured live-call budget.
 * The adapter owns snake_case wire-field mapping and lowercase need-attribute serialization;
 * these Kotlin structures use UUID references and exact minor-unit price limits.
 */
fun interface WishAnalyzer {
    fun analyze(options: List<Option>, wishes: List<Wish>): AnalysisResult

    /** Existing adapters can analyze without reporting intermediate work. */
    fun analyzeTracked(options: List<Option>, wishes: List<Wish>, observer: AnalysisObserver): AnalysisResult =
        analyze(options, wishes)

    /** Classify one wish without proposing a batch; at most one call within fifteen seconds. */
    fun classify(option: Option, wish: Wish): AnalysisResult = AnalysisResult.Failure(AnalysisFailure.NOT_CONFIGURED)
}

package app.campaign.port

import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Money
import app.campaign.model.NeedAttribute
import app.campaign.model.NeedStrength
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishKind
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

/**
 * Normalized output: at most one proposal, zero to two alternatives, and no model-owned offer
 * prices, thresholds, capacities or dates. A missing proposal requires noProposalReason.
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
}

/** Path identifies an output location; message must not contain raw private input. */
data class AnalysisValidationFlag(val rule: AnalysisValidationRule, val path: String, val message: String)

sealed interface AnalysisValidationResult {
    val flags: List<AnalysisValidationFlag>

    data class Accepted(val output: AnalysisOutput, override val flags: List<AnalysisValidationFlag>) : AnalysisValidationResult
    data class Rejected(override val flags: List<AnalysisValidationFlag>) : AnalysisValidationResult
}

enum class AnalysisFailure { INVALID_JSON, INVALID_OUTPUT, PROVIDER_FAILURE, RUN_LIMIT_REACHED }

sealed interface AnalysisResult {
    data class Success(val model: String, val validated: AnalysisValidationResult.Accepted) : AnalysisResult
    data class Failure(val reason: AnalysisFailure, val flags: List<AnalysisValidationFlag> = emptyList()) : AnalysisResult
}

/**
 * Analyze only the supplied snapshot. Success contains validated output; failures preserve the
 * caller's last valid run. Unknown fields are discarded. Invalid JSON or multiple proposals may
 * be retried once only. Implementations enforce their configured live-call budget.
 * The adapter owns snake_case wire-field mapping and lowercase need-attribute serialization;
 * these Kotlin structures use UUID references and exact minor-unit price limits.
 */
fun interface WishAnalyzer {
    fun analyze(options: List<Option>, wishes: List<Wish>): AnalysisResult
}

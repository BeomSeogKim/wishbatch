package app.campaign.offerdraft

import java.time.Duration
import java.util.UUID

data class OfferDraftEvidence(val wishId: UUID, val quote: String)

data class OfferDraftInput(
    val facts: List<String>,
    val evidence: List<OfferDraftEvidence>,
    val sourceHash: String,
)

sealed interface OfferDraftGeneration {
    data class Generated(val json: String, val model: String) : OfferDraftGeneration
    data class Unavailable(val reason: String) : OfferDraftGeneration
}

/** Implementations must reserve the shared auxiliary UTC daily quota and make one request without retry. */
fun interface OfferDraftGenerator {
    fun generate(input: OfferDraftInput, timeout: Duration, maxOutputTokens: Int): OfferDraftGeneration
}

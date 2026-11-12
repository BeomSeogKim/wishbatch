package app.campaign.terms

import java.time.Instant
import java.util.UUID

data class SealedOfferTerms(val offerId: UUID, val termsHash: String, val unitPriceMinor: Long, val currency: String)

/** A stored seal exists but its identity, payload, or hash no longer matches. */
class SealedOfferChanged : RuntimeException("Published offer differs from its sealed terms.")

data class TermsSealView(
    val offerId: UUID,
    val availability: String,
    val termsHash: String?,
    val publishedAt: Instant?,
)

enum class TermsCheckStage { AUTHORIZATION, PRE_CAPTURE }
enum class TermsCheckOutcome { MATCH, MISMATCH, UNAVAILABLE }

data class TermsCheckObservation(
    val version: Int = 1,
    val stage: TermsCheckStage,
    val offerId: UUID?,
    val termsHash: String?,
    val quantity: Int,
    val expectedAmountMinor: Long?,
    val expectedCurrency: String?,
    val orderId: String?,
    val providerOrderId: String?,
    val providerAmountMinor: Long?,
    val providerCurrency: String?,
    val providerStatus: String?,
    val outcome: TermsCheckOutcome,
    val reason: String,
    val checkedAt: Instant,
)

package app.campaign.rules

import app.campaign.model.Offer
import app.campaign.model.Option
import java.time.Duration

enum class OfferViolation {
    MINIMUM_BELOW_OPTION, MINIMUM_EXCEEDS_CAPACITY, NON_POSITIVE_CAPACITY,
    CAPACITY_ABOVE_OPTION, INVALID_DURATION, NON_POSITIVE_PRICE, QUANTITY_OUT_OF_RANGE, CURRENCY_MISMATCH,
}

enum class OfferWarning { PRICE_BELOW_COST }

data class OfferValidation(val violations: Set<OfferViolation>, val warnings: Set<OfferWarning>) {
    val valid: Boolean get() = violations.isEmpty()
}

/**
 * Validates commercial limits against the selected option; a below-cost price only warns.
 * Passing another option or workspace is a caller error, rejected before comparing any limits.
 */
fun validateOffer(offer: Offer, option: Option, duration: Duration): OfferValidation {
    require(offer.optionId == option.id && offer.workspaceId == option.workspaceId) { "Offer must reference the supplied workspace option" }
    val violations = buildSet {
        if (offer.minBatch < option.minBatch || offer.minBatch < 1) add(OfferViolation.MINIMUM_BELOW_OPTION)
        if (offer.minBatch > offer.capacity) add(OfferViolation.MINIMUM_EXCEEDS_CAPACITY)
        if (offer.capacity < 1) add(OfferViolation.NON_POSITIVE_CAPACITY)
        if (offer.capacity > option.maxCapacity) add(OfferViolation.CAPACITY_ABOVE_OPTION)
        if (duration !in ALLOWED_CAMPAIGN_DURATIONS) add(OfferViolation.INVALID_DURATION)
        if (offer.unitPrice <= 0) add(OfferViolation.NON_POSITIVE_PRICE)
        if (offer.maxQtyPerPledge !in 1..offer.capacity) add(OfferViolation.QUANTITY_OUT_OF_RANGE)
        if (offer.currency != option.currency) add(OfferViolation.CURRENCY_MISMATCH)
    }
    val warnings = if (offer.currency == option.currency && offer.unitPrice < option.unitCost) {
        setOf(OfferWarning.PRICE_BELOW_COST)
    } else {
        emptySet()
    }
    return OfferValidation(violations, warnings)
}

enum class CapacityDecision { ALLOWED, INVALID_QUANTITY, CAPACITY_EXCEEDED }

/**
 * Check under the caller's campaign lock both before order creation and after authorization.
 * authorizedUnits excludes the candidate pledge; CREATED orders do not reserve capacity.
 * A post-authorization rejection requires voiding that authorization. This function performs no I/O.
 */
fun checkCapacity(capacity: Int, authorizedUnits: Long, requestedQty: Int, maxQtyPerPledge: Int): CapacityDecision {
    require(capacity > 0 && authorizedUnits >= 0 && maxQtyPerPledge in 1..capacity)
    if (requestedQty !in 1..maxQtyPerPledge) return CapacityDecision.INVALID_QUANTITY
    return if (authorizedUnits > capacity.toLong() - requestedQty) {
        CapacityDecision.CAPACITY_EXCEEDED
    } else {
        CapacityDecision.ALLOWED
    }
}

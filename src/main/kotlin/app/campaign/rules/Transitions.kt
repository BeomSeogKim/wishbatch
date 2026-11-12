package app.campaign.rules

import app.campaign.model.CampaignReason
import app.campaign.model.CampaignState
import app.campaign.model.PledgeState

data class CampaignTransition(val from: CampaignState, val to: CampaignState, val reason: CampaignReason? = null)
data class PledgeTransition(val from: PledgeState, val to: PledgeState)

/**
 * Structural transitions, including the only valid terminal reasons. Callers must also enforce
 * trigger guards: publishing requires a valid offer; confirmation requires thresholdMet and
 * now <= confirmDeadline; close/expiry require the deadline to have elapsed. Settlement uses
 * funded after every capture attempt. Both terminal campaign states have no outgoing edges.
 */
val CAMPAIGN_TRANSITIONS: Set<CampaignTransition> = setOf(
    CampaignTransition(CampaignState.DRAFT, CampaignState.OPEN),
    CampaignTransition(CampaignState.OPEN, CampaignState.SETTLING),
    CampaignTransition(CampaignState.OPEN, CampaignState.AWAITING_CONFIRMATION),
    CampaignTransition(CampaignState.OPEN, CampaignState.NOT_FUNDED, CampaignReason.THRESHOLD_NOT_MET),
    CampaignTransition(CampaignState.OPEN, CampaignState.NOT_FUNDED, CampaignReason.CANCELLED_BY_MAKER),
    CampaignTransition(CampaignState.AWAITING_CONFIRMATION, CampaignState.SETTLING),
    CampaignTransition(CampaignState.AWAITING_CONFIRMATION, CampaignState.NOT_FUNDED, CampaignReason.CONFIRMATION_EXPIRED),
    CampaignTransition(CampaignState.AWAITING_CONFIRMATION, CampaignState.NOT_FUNDED, CampaignReason.CANCELLED_BY_MAKER),
    CampaignTransition(CampaignState.SETTLING, CampaignState.FUNDED),
    CampaignTransition(CampaignState.SETTLING, CampaignState.NOT_FUNDED, CampaignReason.CAPTURE_SHORTFALL),
)

/**
 * Failed captures allow voiding the remaining authorization. Failed voids and refunds allow
 * retries, and pending refunds may later fail during provider reconciliation. Rejected approvals
 * release their holds directly from CREATED; recovery of failed operations follows capture evidence.
 */
val PLEDGE_TRANSITIONS: Set<PledgeTransition> = setOf(
    PledgeTransition(PledgeState.CREATED, PledgeState.AUTHORIZED),
    PledgeTransition(PledgeState.CREATED, PledgeState.ABANDONED),
    PledgeTransition(PledgeState.CREATED, PledgeState.VOIDED),
    PledgeTransition(PledgeState.CREATED, PledgeState.VOID_FAILED),
    PledgeTransition(PledgeState.AUTHORIZED, PledgeState.CAPTURED),
    PledgeTransition(PledgeState.AUTHORIZED, PledgeState.CAPTURE_PENDING),
    PledgeTransition(PledgeState.AUTHORIZED, PledgeState.CAPTURE_FAILED),
    PledgeTransition(PledgeState.AUTHORIZED, PledgeState.VOIDED),
    PledgeTransition(PledgeState.AUTHORIZED, PledgeState.VOID_FAILED),
    PledgeTransition(PledgeState.CAPTURED, PledgeState.REFUNDED),
    PledgeTransition(PledgeState.CAPTURED, PledgeState.REFUND_PENDING),
    PledgeTransition(PledgeState.CAPTURED, PledgeState.REFUND_FAILED),
    PledgeTransition(PledgeState.CAPTURED, PledgeState.PARTIALLY_REFUNDED),
    PledgeTransition(PledgeState.CAPTURE_PENDING, PledgeState.CAPTURED),
    PledgeTransition(PledgeState.CAPTURE_PENDING, PledgeState.CAPTURE_FAILED),
    PledgeTransition(PledgeState.CAPTURE_FAILED, PledgeState.CAPTURED),
    PledgeTransition(PledgeState.CAPTURE_FAILED, PledgeState.CAPTURE_PENDING),
    PledgeTransition(PledgeState.VOID_FAILED, PledgeState.CAPTURED),
    PledgeTransition(PledgeState.VOID_FAILED, PledgeState.CAPTURE_PENDING),
    PledgeTransition(PledgeState.CAPTURE_FAILED, PledgeState.VOIDED),
    PledgeTransition(PledgeState.CAPTURE_FAILED, PledgeState.VOID_FAILED),
    PledgeTransition(PledgeState.VOID_FAILED, PledgeState.VOIDED),
    PledgeTransition(PledgeState.REFUND_PENDING, PledgeState.REFUNDED),
    PledgeTransition(PledgeState.REFUND_PENDING, PledgeState.REFUND_FAILED),
    PledgeTransition(PledgeState.REFUND_PENDING, PledgeState.PARTIALLY_REFUNDED),
    PledgeTransition(PledgeState.REFUND_FAILED, PledgeState.REFUNDED),
    PledgeTransition(PledgeState.REFUND_FAILED, PledgeState.REFUND_PENDING),
    PledgeTransition(PledgeState.REFUND_FAILED, PledgeState.PARTIALLY_REFUNDED),
    PledgeTransition(PledgeState.PARTIALLY_REFUNDED, PledgeState.REFUNDED),
)

/** Reject illegal edges and reasons; return the target for assignment after guarded work succeeds. */
fun requireCampaignTransition(from: CampaignState, to: CampaignState, reason: CampaignReason? = null): CampaignState {
    require(CampaignTransition(from, to, reason) in CAMPAIGN_TRANSITIONS) { "Illegal campaign transition: $from -> $to ($reason)" }
    return to
}

/** Provider reconciliation still follows these edges; repeated callbacks are no-ops at the caller. */
fun requirePledgeTransition(from: PledgeState, to: PledgeState): PledgeState {
    require(PledgeTransition(from, to) in PLEDGE_TRANSITIONS) { "Illegal pledge transition: $from -> $to" }
    return to
}

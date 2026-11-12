package app.campaign.outcome

import app.campaign.model.PledgeState

enum class OutcomeStep { AUTHORIZATION, HOLD, CAPTURE, RELEASE, REFUND }

fun outcomeStep(state: PledgeState): OutcomeStep = when (state) {
    PledgeState.CREATED, PledgeState.ABANDONED -> OutcomeStep.AUTHORIZATION
    PledgeState.AUTHORIZED -> OutcomeStep.HOLD
    PledgeState.CAPTURE_PENDING, PledgeState.CAPTURE_FAILED, PledgeState.CAPTURED -> OutcomeStep.CAPTURE
    PledgeState.VOIDED, PledgeState.VOID_FAILED -> OutcomeStep.RELEASE
    PledgeState.REFUND_PENDING, PledgeState.REFUND_FAILED, PledgeState.REFUNDED,
    PledgeState.PARTIALLY_REFUNDED -> OutcomeStep.REFUND
}

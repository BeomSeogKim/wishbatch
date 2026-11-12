package app.campaign.rules

import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.model.WishKind
import app.campaign.port.AnalyzedWish

/** Count distinct requests in validated analysis, not requested quantities or payment records. */
fun interestCount(wishes: Iterable<AnalyzedWish>): Int =
    wishes.filter { it.kind == WishKind.REQUEST }.map { it.wishId }.distinct().size

/** Callers supply pledges from one campaign. Pending and failed operations contribute no units. */
fun authorizedUnits(pledges: Iterable<Pledge>): Long =
    pledges.filter { it.state == PledgeState.AUTHORIZED }.sumOf { it.qty.toLong() }

fun capturedUnits(pledges: Iterable<Pledge>): Long =
    pledges.filter { it.state == PledgeState.CAPTURED }.sumOf { it.qty.toLong() }

fun thresholdMet(authorizedUnits: Long, minBatch: Int): Boolean {
    require(authorizedUnits >= 0 && minBatch > 0)
    return authorizedUnits >= minBatch
}

/** Only this completed-payment threshold can release production. */
fun funded(capturedUnits: Long, minBatch: Int): Boolean {
    require(capturedUnits >= 0 && minBatch > 0)
    return capturedUnits >= minBatch
}

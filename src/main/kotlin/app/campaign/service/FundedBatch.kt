package app.campaign.service

import app.campaign.model.PledgeState
import app.campaign.model.ShippingAddress
import java.math.BigInteger
import java.util.UUID

data class BatchPledge(
    val id: UUID,
    val buyerName: String?,
    val quantity: Int,
    val shippingAddress: ShippingAddress?,
    val state: PledgeState,
    val payerEmailMasked: String?,
)

data class FundedBatch(
    val paid: List<BatchPledge>,
    val incomplete: List<BatchPledge>,
    val totalUnits: Long,
    val totalAmount: BigInteger,
    val currency: String,
)

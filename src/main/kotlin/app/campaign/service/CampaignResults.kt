package app.campaign.service

import app.campaign.model.CampaignReason
import app.campaign.model.CampaignState
import app.campaign.model.PledgeState
import app.campaign.rules.OfferValidation
import app.campaign.rules.OfferWarning
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class CampaignRef(val workspaceId: UUID, val campaignId: UUID)
data class PledgeRef(val campaign: CampaignRef, val pledgeId: UUID)

enum class Rejection {
    NOT_FOUND, INVALID_OFFER, NO_PROPOSAL, INVALID_EVIDENCE, ACTIVE_CAMPAIGN,
    INVALID_STATE, INVALID_QUANTITY, CAPACITY_EXCEEDED, AMOUNT_OVERFLOW,
    PAYMENT_UNAVAILABLE, PAYMENT_REJECTED, PAYMENT_MISMATCH, THRESHOLD_NOT_MET,
    SIMULATION_DISABLED,
}

sealed interface ServiceResult<out T> {
    data class Success<T>(val value: T) : ServiceResult<T>
    data class Rejected(
        val reason: Rejection,
        val pledge: PledgeView? = null,
        val validation: OfferValidation? = null,
    ) : ServiceResult<Nothing>
}

data class DraftOffer(
    val optionId: UUID,
    val analysisRunId: UUID,
    val title: String,
    val specText: String,
    val chosenVariants: Map<String, String>,
    val unitPrice: Long,
    val deliveryTerms: String,
    val minBatch: Int,
    val capacity: Int,
    val duration: Duration,
    val maxQtyPerPledge: Int = 3,
    val supportingWishIds: List<UUID> = emptyList(),
)

data class DraftCreated(val campaignId: UUID, val offerId: UUID, val warnings: Set<OfferWarning>)

data class OfferView(
    val id: UUID,
    val optionId: UUID,
    val analysisRunId: UUID,
    val title: String,
    val specText: String,
    val chosenVariants: Map<String, String>,
    val unitPrice: Long,
    val currency: String,
    val deliveryTerms: String,
    val shipRegion: String,
    val minBatch: Int,
    val capacity: Int,
    val maxQtyPerPledge: Int,
    val supportingWishIds: List<UUID>,
)

data class PledgeView(
    val id: UUID,
    val quantity: Int,
    val amount: Long,
    val currency: String,
    val state: PledgeState,
    val orderId: String?,
    val authorizationId: String?,
    val captureId: String?,
    val refundId: String?,
    val authorizationExpiresAt: Instant?,
    val payerEmailMasked: String?,
    val lastError: String?,
)

data class TimelineEntry(
    val type: String,
    val pledgeId: UUID?,
    val endpoint: String?,
    val providerId: String?,
    val status: String?,
    val debugId: String?,
    val detail: String?,
    val time: Instant,
)

data class CampaignCounts(val statedInterest: Int, val authorizedHolds: Long, val paid: Long)
data class MinimumProgress(val units: Long, val minimum: Int)
data class AttentionItem(val pledgeId: UUID, val state: PledgeState, val reason: String?)
data class ResultSummary(
    val state: CampaignState,
    val reason: CampaignReason?,
    val productionReleased: Boolean,
    val paidUnits: Long,
    val refundedUnits: Long,
    val releasedUnits: Long,
)
data class ConfirmationSummary(val pledgeCount: Int, val units: Long, val amount: Long, val currency: String)

data class CampaignView(
    val id: UUID,
    val offer: OfferView,
    val state: CampaignState,
    val reason: CampaignReason?,
    val openedAt: Instant?,
    val closesAt: Instant?,
    val confirmDeadline: Instant?,
    val counts: CampaignCounts,
    val progress: MinimumProgress,
    val capacityLeft: Long,
    val timeLeft: Duration,
    val confirmation: ConfirmationSummary,
    val pledges: List<PledgeView>,
    val result: ResultSummary,
    val needsAttention: List<AttentionItem>,
    val timeline: List<TimelineEntry>,
)

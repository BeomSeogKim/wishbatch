package app.campaign.trail

import app.campaign.model.Event
import app.campaign.model.Pledge
import app.campaign.store.CampaignRepository
import app.campaign.store.EventRepository
import app.campaign.store.OfferRepository
import app.campaign.store.PledgeRepository
import app.campaign.store.WorkspaceRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

data class BooksCheckpoint(val id: UUID, val checkpoint: Instant)

data class TrailEntry(val pledgeId: UUID, val checkpoint: Instant, val status: String, val providerId: String?, val amount: String)

fun trailEntries(pledges: List<Pledge>, events: List<Event>): List<TrailEntry> {
    val byId = pledges.associateBy { it.id }
    return events.mapNotNull { event ->
        val pledge = byId[event.pledgeId] ?: return@mapNotNull null
        if (event.type != "PLEDGE_STATE") return@mapNotNull null
        val state = event.detail?.substringAfter(" -> ", "") ?: return@mapNotNull null
        val (label, id, amount) = when (state) {
            "AUTHORIZED" -> Triple("Hold confirmed", pledge.authorizationId, "${pledge.amount} ${pledge.currency} minor units")
            "CAPTURED" -> Triple("Payment confirmed", pledge.captureId, "${pledge.amount} ${pledge.currency} minor units")
            "CAPTURE_PENDING" -> Triple("Payment pending", pledge.captureId, "Unknown")
            "CAPTURE_FAILED" -> Triple("Payment failed", pledge.captureId, "Unknown")
            "VOIDED" -> if (event.detail?.startsWith("CREATED ->") == true) Triple("Order closed", pledge.paypalOrderId, "Unknown")
                else Triple("Hold released", pledge.authorizationId, "Unknown")
            "VOID_FAILED" -> Triple("Release unconfirmed", pledge.authorizationId, "Unknown")
            "REFUNDED" -> Triple("Refund confirmed", pledge.refundId, "Unknown")
            "PARTIALLY_REFUNDED" -> Triple("Partial refund observed", pledge.captureId, "Unknown")
            "REFUND_PENDING" -> Triple("Refund pending", pledge.refundId, "Unknown")
            "REFUND_FAILED" -> Triple("Refund failed", pledge.refundId, "Unknown")
            else -> return@mapNotNull null
        }
        TrailEntry(pledge.id, event.createdAt, label, id, amount)
    }.distinctBy { Triple(it.pledgeId, it.status, it.providerId) }
}

@Service
class MoneyTrail(
    private val workspaces: WorkspaceRepository,
    private val campaigns: CampaignRepository,
    private val offers: OfferRepository,
    private val pledges: PledgeRepository,
    private val events: EventRepository,
) {
    fun campaign(token: String, campaignId: UUID): List<TrailEntry>? {
        val workspace = workspaces.findByToken(token) ?: return null
        val campaign = campaigns.findById(campaignId).orElse(null) ?: return null
        if (offers.findByIdAndWorkspaceId(campaign.offerId, workspace.id) == null) return null
        return trailEntries(pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(campaignId),
            events.findByCampaignIdOrderByCreatedAtAscIdAsc(campaignId))
    }

    fun booksChecks(token: String, campaignId: UUID): List<BooksCheckpoint>? {
        if (campaign(token, campaignId) == null) return null
        return events.findByCampaignIdOrderByCreatedAtAscIdAsc(campaignId)
            .filter { it.type == "BOOKS_CHECK" }
            .map { BooksCheckpoint(it.id, it.createdAt) }
    }

    fun pledge(token: String, pledgeId: UUID): List<TrailEntry>? {
        val pledge = pledges.findById(pledgeId).orElse(null) ?: return null
        return campaign(token, pledge.campaignId)?.filter { it.pledgeId == pledgeId }
    }
}

package app.campaign.charts

import app.campaign.model.CampaignState
import app.campaign.model.PledgeState
import app.campaign.rules.authorizedUnits
import app.campaign.rules.capturedUnits
import app.campaign.service.OfferInterestQuery
import app.campaign.store.AnalysisRunRepository
import app.campaign.store.CampaignRepository
import app.campaign.store.EventRepository
import app.campaign.store.OfferRepository
import app.campaign.store.PledgeRepository
import app.campaign.store.WorkspaceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class CampaignCharts(
    val campaignId: UUID,
    val state: CampaignState,
    val observedAt: Instant,
    val minimum: Int,
    val interest: Int?,
    val authorized: Long,
    val paid: Long,
    val analysisAt: Instant?,
    val attention: List<String>,
    val history: CountHistory,
    val interestHistoryMessage: String,
    val conditional: Int?,
    val excludedByPrice: Int?,
    val notServed: Int?,
    val interestWishIds: List<UUID>?,
    val conditionalWishIds: List<UUID>?,
    val excludedByPriceWishIds: List<UUID>?,
    val notServedWishIds: List<UUID>?,
)

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class CampaignChartsQuery(
    private val workspaces: WorkspaceRepository,
    private val campaigns: CampaignRepository,
    private val offers: OfferRepository,
    private val analyses: AnalysisRunRepository,
    private val pledges: PledgeRepository,
    private val events: EventRepository,
    private val clock: Clock,
    private val interestQuery: OfferInterestQuery,
) {
    fun read(token: String, campaignId: UUID): CampaignCharts? {
        val workspace = workspaces.findByToken(token) ?: return null
        val campaign = campaigns.findById(campaignId).orElse(null) ?: return null
        val offer = offers.findByIdAndWorkspaceId(campaign.offerId, workspace.id) ?: return null
        val rows = pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id)
        val recorded = events.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id)
        val run = analyses.findByIdAndWorkspaceId(offer.analysisRunId, workspace.id)
        val counts = interestQuery.read(workspace.id, offer.analysisRunId, offer.optionId, offer.unitPrice)
        val interest = counts?.interest
        val publication = recorded.filter { it.type == "CAMPAIGN_STATE" && it.detail == "DRAFT -> OPEN" }
            .singleOrNull()?.takeIf { event ->
                val opened = campaign.openedAt
                run != null && opened != null && run.createdAt <= opened && event.createdAt >= opened
            }
        val authorized = authorizedUnits(rows)
        val paid = capturedUnits(rows)
        val attention = buildList {
            if (campaign.needsAttention) add("The campaign is marked as needing payment attention.")
            if (campaign.state == CampaignState.FUNDED && paid < offer.minBatch) {
                add("FUNDED is a historical outcome. Current completed payments are below the minimum; review payment attention before production.")
            }
            if (rows.any { it.state in setOf(PledgeState.CAPTURE_PENDING, PledgeState.REFUND_PENDING,
                    PledgeState.REFUND_FAILED, PledgeState.VOID_FAILED) || it.lastError != null }) {
                add("Pending or unresolved payments need review; this page does not contact PayPal or reconcile them.")
            }
            if (rows.any { it.state == PledgeState.PARTIALLY_REFUNDED }) {
                add("A partial refund is recorded. The remainder is unknown and is excluded from fully paid units.")
            }
        }
        return CampaignCharts(campaign.id, campaign.state, clock.instant(), offer.minBatch, interest, authorized, paid,
            run?.createdAt, attention, campaignCountHistory(campaign.id, rows, recorded, publication),
            "Historical offer interest is unavailable: visibility and reviewed conditions were not recorded at each checkpoint.",
            counts?.conditional, counts?.excludedByPrice, counts?.notServed,
            counts?.interestWishIds, counts?.conditionalWishIds, counts?.excludedByPriceWishIds, counts?.notServedWishIds)
    }
}

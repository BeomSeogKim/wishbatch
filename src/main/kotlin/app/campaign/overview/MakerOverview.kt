package app.campaign.overview

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.books.BooksCheckService
import app.campaign.books.BooksMatch
import app.campaign.followup.FollowupService
import app.campaign.model.Campaign
import app.campaign.model.CampaignState
import app.campaign.model.PledgeState
import app.campaign.provenance.DecisionRecordQuery
import app.campaign.rules.authorizedUnits
import app.campaign.rules.capturedUnits
import app.campaign.service.OfferInterestQuery
import app.campaign.service.CampaignRef
import app.campaign.service.ServiceResult
import app.campaign.store.CampaignRepository
import app.campaign.store.OfferRepository
import app.campaign.store.PledgeRepository
import app.campaign.workspace.WorkspaceNotFoundException
import app.campaign.workspace.WorkspaceService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class OverviewAction(val label: String, val path: String)
data class MakerOverview(
    val campaignId: UUID?, val title: String?, val state: CampaignState?, val reason: String?,
    val deadline: Instant?, val expired: Boolean, val minimum: Int?, val interest: Int?,
    val authorized: Long?, val paid: Long?, val attention: List<String>, val action: OverviewAction,
    val analysisAt: Instant?, val newWishes: Int?, val questionDrafts: Int,
    val booksAt: Instant?, val booksUnknown: Int?, val booksMismatch: Int?,
    val unavailableRecords: Int, val changedRecords: Int,
    val conditional: Int? = null,
)

internal fun nextAction(state: CampaignState?, expired: Boolean, attention: Boolean, authorized: Long, minimum: Int?, hasAnalysis: Boolean): OverviewAction = when {
    attention -> OverviewAction("Review payment attention", "campaign")
    state == null && !hasAnalysis -> OverviewAction("Review wishes and analysis", "proposal")
    state == null || state == CampaignState.DRAFT -> OverviewAction("Review proposal", "proposal")
    state in setOf(CampaignState.OPEN, CampaignState.AWAITING_CONFIRMATION) && !expired && minimum != null && authorized >= minimum -> OverviewAction("Review confirmation", "campaign?review=true")
    state == CampaignState.OPEN || state == CampaignState.AWAITING_CONFIRMATION -> OverviewAction("Review campaign", "campaign")
    state == CampaignState.SETTLING -> OverviewAction("Review settlement", "campaign")
    else -> OverviewAction("Review result", "result")
}

@Service
@Transactional(readOnly = true)
class MakerOverviewQuery(
    private val workspaces: WorkspaceService,
    private val campaigns: CampaignRepository,
    private val offers: OfferRepository,
    private val pledges: PledgeRepository,
    private val analyses: AnalysisRunService,
    private val books: BooksCheckService,
    private val followups: FollowupService,
    private val decisions: DecisionRecordQuery,
    private val clock: Clock,
    private val interestQuery: OfferInterestQuery,
) {
    fun read(token: String): MakerOverview {
        val workspace = workspaces.findByToken(token) ?: throw WorkspaceNotFoundException()
        val records = campaigns.findByWorkspaceId(workspace.id)
        val campaign = records.filter { it.openedAt != null }.maxWithOrNull(
            compareBy<Campaign> { it.state !in setOf(CampaignState.FUNDED, CampaignState.NOT_FUNDED) }
                .thenBy { it.openedAt }.thenBy { it.id },
        ) ?: records.filter { it.state == CampaignState.DRAFT }.maxByOrNull { it.id }
        val offer = campaign?.let { offers.findByIdAndWorkspaceId(it.offerId, workspace.id) }
        val rows = campaign?.let { pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(it.id) }.orEmpty()
        val authorized = authorizedUnits(rows)
        val paid = capturedUnits(rows)
        val latest = analyses.latest(token)
        val analysis = if (offer == null) latest else analyses.find(token, offer.analysisRunId)
        val attention = buildList {
            rows.groupingBy { it.state }.eachCount().forEach { (state, count) ->
                if (state in attentionStates) add("$count pledge(s): ${state.name.lowercase().replace('_', ' ')}")
            }
            if (rows.any { it.state == PledgeState.CREATED && it.lastError == "AUTHORIZATION_UNCONFIRMED" ||
                    it.state in setOf(PledgeState.AUTHORIZED, PledgeState.CAPTURED) && it.lastError != null }) {
                add("A payment has an unresolved error or unconfirmed authorization.")
            }
            if (campaign?.state == CampaignState.FUNDED && offer != null && paid < offer.minBatch) {
                add("FUNDED is a historical outcome; currently recorded paid units are below the minimum.")
            }
            if (campaign?.needsAttention == true && isEmpty()) add("The campaign is marked as needing attention.")
        }
        val deadline = when (campaign?.state) {
            CampaignState.OPEN -> campaign.closesAt
            CampaignState.AWAITING_CONFIRMATION -> campaign.confirmDeadline
            else -> null
        }
        val expired = deadline != null && !deadline.isAfter(clock.instant())
        val check = campaign?.let { (books.history(CampaignRef(workspace.id, it.id)) as? ServiceResult.Success)?.value?.lastOrNull() }
        val decision = decisions.findByToken(token) ?: throw WorkspaceNotFoundException()
        val relevant = if (campaign == null) emptyList() else decision.pledges.filter { it.campaignId == campaign.id }
        val interest = offer?.let { interestQuery.read(workspace.id, it.analysisRunId, it.optionId, it.unitPrice) }
        return MakerOverview(campaign?.id, offer?.title, campaign?.state, campaign?.reason?.name, deadline, expired,
            offer?.minBatch, interest?.interest,
            campaign?.let { authorized }, campaign?.let { paid }, attention,
            nextAction(campaign?.state, expired, attention.isNotEmpty(), authorized, offer?.minBatch, latest != null),
            analysis?.run?.createdAt, analysis?.newWishCount, followups.makerPage(token).questions.count { !it.approved },
            check?.checkedAt, check?.pledges?.count { it.after.match == BooksMatch.UNKNOWN },
            check?.pledges?.count { it.after.match == BooksMatch.MISMATCH },
            relevant.count { it.integrity == "UNAVAILABLE" }, relevant.count { it.integrity == "CHANGED" }, interest?.conditional)
    }

    private val attentionStates = setOf(PledgeState.CAPTURE_PENDING, PledgeState.REFUND_PENDING,
        PledgeState.REFUND_FAILED, PledgeState.VOID_FAILED, PledgeState.PARTIALLY_REFUNDED)
}

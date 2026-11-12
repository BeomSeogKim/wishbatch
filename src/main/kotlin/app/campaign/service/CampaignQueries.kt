package app.campaign.service

import app.campaign.model.Campaign
import app.campaign.model.CampaignState
import app.campaign.model.PledgeState
import app.campaign.rules.CapacityDecision
import app.campaign.rules.authorizedUnits
import app.campaign.rules.checkCapacity
import app.campaign.store.CampaignRepository
import app.campaign.store.EventRepository
import app.campaign.store.OfferRepository
import app.campaign.store.PledgeRepository
import app.campaign.store.WorkspaceRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class PledgeQuote(
    val campaignId: UUID,
    val quantity: Int,
    val amount: Long,
    val currency: String,
    val decision: CapacityDecision,
) {
    val allowed: Boolean get() = decision == CapacityDecision.ALLOWED
}

data class PledgeLookup(val pledge: PledgeView, val createdAt: Instant, val campaign: CampaignView) {
    val canWithdraw: Boolean get() = campaign.state == CampaignState.OPEN &&
        !campaign.timeLeft.isZero && pledge.state == PledgeState.AUTHORIZED
}
data class MakerOffers(val draft: CampaignView?, val latest: CampaignView?, val history: List<CampaignView>)

@Service
class CampaignQueries(
    private val service: CampaignService,
    private val workspaces: WorkspaceRepository,
    private val campaigns: CampaignRepository,
    private val offers: OfferRepository,
    private val pledges: PledgeRepository,
    private val events: EventRepository,
    private val clock: Clock,
) {
    fun currentCampaign(workspaceToken: String): ServiceResult<CampaignView?> {
        val workspace = workspaces.findByToken(workspaceToken) ?: return notFound()
        val campaign = selectCurrent(campaigns.findByWorkspaceId(workspace.id))
            ?: return ServiceResult.Success(null)
        return service.readCampaign(CampaignRef(workspace.id, campaign.id))
    }

    /** Checks the pledge window without reconciliation or payment calls; capacity is checked by quote. */
    fun isOpenForPledges(workspaceToken: String): Boolean =
        campaigns.existsOpenByToken(workspaceToken, CampaignState.OPEN, clock.instant())

    /** A snapshot, not a reservation. Starting a pledge checks capacity again under the campaign lock. */
    fun quote(workspaceToken: String, quantity: Int): ServiceResult<PledgeQuote> {
        val workspace = workspaces.findByToken(workspaceToken) ?: return notFound()
        val campaign = campaigns.findByWorkspaceId(workspace.id).firstOrNull {
            it.state == CampaignState.OPEN && it.closesAt?.isAfter(clock.instant()) == true
        } ?: return ServiceResult.Rejected(Rejection.INVALID_STATE)
        val offer = offers.findByIdAndWorkspaceId(campaign.offerId, workspace.id) ?: return notFound()
        val decision = checkCapacity(offer.capacity,
            authorizedUnits(pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id)), quantity, offer.maxQtyPerPledge)
        val amount = try {
            Math.multiplyExact(offer.unitPrice, quantity.toLong())
        } catch (_: ArithmeticException) {
            return ServiceResult.Rejected(Rejection.AMOUNT_OVERFLOW)
        }
        return ServiceResult.Success(PledgeQuote(campaign.id, quantity, amount, offer.currency, decision))
    }

    fun lookupPledge(workspaceToken: String, pledgeId: UUID): ServiceResult<PledgeLookup> {
        val workspace = workspaces.findByToken(workspaceToken) ?: return notFound()
        val pledge = pledges.findById(pledgeId).orElse(null) ?: return notFound()
        val campaign = campaigns.findById(pledge.campaignId).orElse(null) ?: return notFound()
        if (offers.findByIdAndWorkspaceId(campaign.offerId, workspace.id) == null) return notFound()
        return when (val result = service.readCampaign(CampaignRef(workspace.id, campaign.id))) {
            is ServiceResult.Rejected -> result
            is ServiceResult.Success -> {
                val view = result.value.pledges.firstOrNull { it.id == pledgeId } ?: return notFound()
                ServiceResult.Success(PledgeLookup(view, pledge.createdAt, result.value))
            }
        }
    }

    fun makerOffers(workspaceToken: String): ServiceResult<MakerOffers> {
        val workspace = workspaces.findByToken(workspaceToken) ?: return notFound()
        val records = campaigns.findByWorkspaceId(workspace.id)
        val draft = records.filter { it.state == CampaignState.DRAFT }.maxWithOrNull(
            compareBy<Campaign> { campaign ->
                events.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id)
                    .firstOrNull { it.type == "CAMPAIGN_CREATED" }?.createdAt ?: Instant.MIN
            }.thenBy { it.id })
        val published = records.filter { it.state != CampaignState.DRAFT }.sortedWith(newestFirst)
        val views = mutableListOf<CampaignView>()
        for (campaign in listOfNotNull(draft) + published) {
            when (val result = service.readCampaign(CampaignRef(workspace.id, campaign.id))) {
                is ServiceResult.Rejected -> return result
                is ServiceResult.Success -> views.add(result.value)
            }
        }
        return ServiceResult.Success(MakerOffers(
            views.firstOrNull { it.state == CampaignState.DRAFT },
            views.firstOrNull { it.state != CampaignState.DRAFT },
            views.filter { it.state in terminalStates },
        ))
    }

    private fun selectCurrent(records: List<Campaign>): Campaign? =
        records.filter { it.state in activeStates }.sortedWith(newestFirst).firstOrNull()
            ?: records.filter { it.state in terminalStates }.sortedWith(newestFirst).firstOrNull()

    private fun notFound() = ServiceResult.Rejected(Rejection.NOT_FOUND)
    private val activeStates = setOf(CampaignState.OPEN, CampaignState.AWAITING_CONFIRMATION, CampaignState.SETTLING)
    private val terminalStates = setOf(CampaignState.FUNDED, CampaignState.NOT_FUNDED)
    private val newestFirst = compareByDescending<Campaign> { it.openedAt ?: Instant.MIN }.thenByDescending { it.id }
}

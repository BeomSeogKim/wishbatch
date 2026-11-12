package app.campaign.service

import app.campaign.store.CampaignRepository
import app.campaign.store.OfferRepository
import app.campaign.store.PledgeRepository
import app.campaign.store.WorkspaceRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class PledgeReferences(
    private val workspaces: WorkspaceRepository,
    private val campaigns: CampaignRepository,
    private val offers: OfferRepository,
    private val pledges: PledgeRepository,
) {
    /** Resolve ownership without reconciliation, so an approval reaches completePledge before deadline cleanup. */
    fun find(workspaceToken: String, pledgeId: UUID): ServiceResult<PledgeRef> {
        val workspace = workspaces.findByToken(workspaceToken) ?: return notFound()
        val pledge = pledges.findById(pledgeId).orElse(null) ?: return notFound()
        val campaign = campaigns.findById(pledge.campaignId).orElse(null) ?: return notFound()
        if (offers.findByIdAndWorkspaceId(campaign.offerId, workspace.id) == null) return notFound()
        return ServiceResult.Success(PledgeRef(CampaignRef(workspace.id, campaign.id), pledge.id))
    }

    private fun notFound() = ServiceResult.Rejected(Rejection.NOT_FOUND)
}

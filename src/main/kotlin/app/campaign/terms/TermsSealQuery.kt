package app.campaign.terms

import app.campaign.provenance.DecisionRecordService
import app.campaign.store.CampaignRepository
import app.campaign.store.OfferRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class TermsSealQuery(
    private val offers: OfferRepository,
    private val campaigns: CampaignRepository,
    private val records: DecisionRecordService,
) {
    fun findByOffer(workspaceId: UUID, offerId: UUID): TermsSealView? {
        offers.findByIdAndWorkspaceId(offerId, workspaceId) ?: return null
        val publishedAt = campaigns.findByOfferIdIn(listOf(offerId)).singleOrNull()?.openedAt
        return try {
            val seal = records.sealedOffer(workspaceId, offerId)
            TermsSealView(offerId, if (seal == null) "UNAVAILABLE" else "AVAILABLE", seal?.termsHash, publishedAt)
        } catch (_: SealedOfferChanged) {
            TermsSealView(offerId, "MISMATCH", null, publishedAt)
        }
    }
}

package app.web.livecampaign

import app.campaign.model.CampaignState
import app.campaign.service.CampaignQueries
import app.campaign.service.CampaignView
import app.campaign.service.ServiceResult
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class LiveCampaignResponse(val availability: String, val observedAt: Instant, val campaign: LiveCampaign?)
data class LiveCounts(val statedInterest: String, val authorizedHolds: String, val paid: String)
data class LiveProgress(val units: String, val minimum: String, val basis: String)
data class LiveCampaign(
    val id: UUID,
    val state: CampaignState,
    val counts: LiveCounts,
    val progress: LiveProgress,
    val capacityLeft: String,
    val closesAt: Instant?,
    val confirmDeadline: Instant?,
    val pledgesOpen: Boolean,
    val fundedPaidShortfall: Boolean,
    val needsAttention: Boolean,
    val confirmationVersion: String,
)

@Component("liveCampaignDisplay")
class LiveCampaignDisplay {
    fun confirmationVersion(campaign: CampaignView?): String? = campaign?.confirmation?.let {
        val summary = "${it.pledgeCount}:${it.units}:${it.amount}:${it.currency}"
        MessageDigest.getInstance("SHA-256").digest(summary.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

@RestController
class LiveCampaignController(
    private val campaigns: CampaignQueries,
    private val clock: Clock,
    private val display: LiveCampaignDisplay,
) {
    @GetMapping("/w/{token}/api/campaign-status")
    fun status(@PathVariable token: String): ResponseEntity<LiveCampaignResponse> {
        val result = campaigns.currentCampaign(token)
        if (result is ServiceResult.Rejected) {
            return ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build()
        }
        val view = (result as ServiceResult.Success).value
        val observedAt = clock.instant()
        val projection = view?.let {
            val paidBasis = it.state in setOf(CampaignState.SETTLING, CampaignState.FUNDED, CampaignState.NOT_FUNDED)
            val shortfall = it.state == CampaignState.FUNDED && it.counts.paid < it.offer.minBatch
            LiveCampaign(
                it.id, it.state,
                LiveCounts(it.counts.statedInterest.toString(), it.counts.authorizedHolds.toString(), it.counts.paid.toString()),
                LiveProgress(it.progress.units.toString(), it.progress.minimum.toString(), if (paidBasis) "PAID" else "HOLDS"),
                it.capacityLeft.toString(), it.closesAt, it.confirmDeadline,
                it.state == CampaignState.OPEN && it.closesAt?.isAfter(observedAt) == true,
                shortfall, it.needsAttention.isNotEmpty() || shortfall, display.confirmationVersion(it)!!,
            )
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(LiveCampaignResponse(if (projection == null) "NO_CAMPAIGN" else "CAMPAIGN", observedAt, projection))
    }
}

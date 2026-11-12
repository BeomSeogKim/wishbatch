package app.campaign.drills

import app.campaign.books.BooksPledgeRow
import app.campaign.model.CampaignReason
import app.campaign.model.CampaignState
import app.campaign.model.PledgeState
import app.campaign.service.CampaignCounts
import java.util.UUID

data class DrillSnapshot(
    val campaignState: CampaignState,
    val reason: CampaignReason?,
    val counts: CampaignCounts,
    val needsAttention: List<String>,
    val pledgeStates: Map<UUID, PledgeState>,
)

data class DrillStep(
    val timeLabel: String,
    val operation: String,
    val endpoint: String?,
    val providerId: String?,
    val key: String?,
    val status: String,
    val detail: String,
    val effective: Boolean,
    val snapshot: DrillSnapshot,
)

data class DrillResult(
    val id: String,
    val title: String,
    val summary: String,
    val before: DrillSnapshot,
    val after: DrillSnapshot,
    val books: List<BooksPledgeRow>,
    val steps: List<DrillStep>,
    val writeEffects: Map<String, Int>,
    val webhookProcessings: Int,
)

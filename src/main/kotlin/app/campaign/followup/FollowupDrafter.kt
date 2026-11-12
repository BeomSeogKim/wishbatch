package app.campaign.followup

import app.campaign.model.Option
import app.campaign.port.AnalysisFailure
import app.campaign.port.AnalysisOutput

sealed interface FollowupDraftResult {
    data class Drafted(val drafts: List<FollowupDraft>, val model: String) : FollowupDraftResult
    data class Failed(val reason: AnalysisFailure) : FollowupDraftResult
}

fun interface FollowupDrafter {
    fun draft(option: Option, output: AnalysisOutput): FollowupDraftResult
}

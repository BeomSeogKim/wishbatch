package app.analysis.followup

import app.analysis.RationaleNumbers
import app.campaign.followup.FollowupDraft
import app.campaign.followup.FollowupQuestions
import app.campaign.model.Option
import app.campaign.port.AnalysisOutput

object FollowupDraftValidator {
    fun validate(drafts: List<FollowupDraft>, option: Option, output: AnalysisOutput): List<FollowupDraft> {
        FollowupQuestions.validate(drafts, option, output)
        require(drafts.none { RationaleNumbers.containsNumber(it.text, option) }) {
            "Model questions cannot introduce numeric terms"
        }
        return drafts.map { it.copy(choices = it.choices.toList(), wishIds = it.wishIds.toList()) }
    }
}

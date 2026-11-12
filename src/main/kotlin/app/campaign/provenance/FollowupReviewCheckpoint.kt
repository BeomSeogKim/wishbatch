package app.campaign.provenance

import app.campaign.followup.FollowupReviewRecorded
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class FollowupReviewCheckpoint(private val records: DecisionRecordService) {
    @EventListener
    fun record(event: FollowupReviewRecorded) {
        records.recordWishReview(event.review, event.answers, event.questions)
    }
}

package app.campaign.followup

import app.campaign.review.WishReviewOverride

data class FollowupReviewRecorded(
    val review: WishReviewOverride,
    val answers: List<FollowupAnswerRecord>,
    val questions: List<FollowupQuestion>,
)

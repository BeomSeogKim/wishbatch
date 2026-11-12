package app.campaign.followup

import app.campaign.model.NeedAttribute
import app.campaign.model.StringListConverter
import app.campaign.model.UuidListConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "followup_draft_attempts")
class FollowupDraftAttempt(
    @Id val runId: UUID,
    @Column(nullable = false) val workspaceId: UUID,
    @Column(nullable = false) val createdAt: Instant,
    var failure: String? = null,
    var model: String? = null,
)

@Entity
@Table(name = "followup_questions")
class FollowupQuestion(
    @Column(nullable = false) val workspaceId: UUID,
    @Column(nullable = false) val runId: UUID,
    @Column(nullable = false) val optionId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val attribute: NeedAttribute,
    @Column(nullable = false) val variantKey: String,
    @Column(name = "question_text", nullable = false, length = 240) var text: String,
    @Convert(converter = StringListConverter::class) @Column(nullable = false, columnDefinition = "text")
    var choices: List<String>,
    @Convert(converter = UuidListConverter::class) @Column(nullable = false, columnDefinition = "text")
    val wishIds: List<UUID>,
    @Column(nullable = false) val createdAt: Instant,
    @Column(nullable = false) var approved: Boolean = false,
    @Id val id: UUID = UUID.randomUUID(),
) {
    fun draft() = FollowupDraft(text, attribute, variantKey, choices, wishIds)
    fun approvedQuestion() = ApprovedFollowupQuestion(id, runId, optionId, draft())
}

@Entity
@Table(name = "followup_answers", uniqueConstraints = [UniqueConstraint(columnNames = ["questionId", "wishId"])])
class FollowupAnswerRecord(
    @Column(nullable = false) val workspaceId: UUID,
    @Column(nullable = false) val runId: UUID,
    @Column(nullable = false) val optionId: UUID,
    @Column(nullable = false) val questionId: UUID,
    @Column(nullable = false) val wishId: UUID,
    @Column(nullable = false) val choice: String,
    @Column(nullable = false) val createdAt: Instant,
    @Id val id: UUID = UUID.randomUUID(),
) {
    fun answer() = FollowupAnswer(id, questionId, wishId, choice)
}

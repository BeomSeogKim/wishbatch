package app.campaign.offerdraft

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "offer_text_drafts", uniqueConstraints = [UniqueConstraint(columnNames = ["runId", "attemptNumber"])])
class OfferTextDraft(
    @Column(nullable = false) val workspaceId: UUID,
    @Column(nullable = false) val runId: UUID,
    @Column(nullable = false) val optionId: UUID,
    @Column(nullable = false) val attemptNumber: Int,
    @Column(nullable = false, length = 64) val sourceHash: String,
    @Column(nullable = false, columnDefinition = "text") val evidenceJson: String,
    @Column(nullable = false) val createdAt: Instant,
    @Column(length = 120) val generatedTitle: String? = null,
    @Column(columnDefinition = "text") val generatedDescription: String? = null,
    @Column(nullable = false, columnDefinition = "text") val removalsJson: String = "[]",
    val failure: String? = null,
    val model: String? = null,
    @Column(length = 120) var reviewedTitle: String? = null,
    @Column(columnDefinition = "text") var reviewedDescription: String? = null,
    var reviewedAt: Instant? = null,
    @Id val id: UUID = UUID.randomUUID(),
)

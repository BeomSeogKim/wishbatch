package app.campaign.review

import app.campaign.model.CompatibilityVerdict
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/** Append-only decisions preserve the model snapshot, source, and earlier decisions. */
@Entity
@Table(name = "wish_review_overrides", uniqueConstraints = [
    UniqueConstraint(columnNames = ["runId", "wishId", "optionId", "revision"]),
])
class WishReviewOverride(
    @Column(nullable = false) val workspaceId: UUID,
    @Column(nullable = false) val runId: UUID,
    @Column(nullable = false) val wishId: UUID,
    @Column(nullable = false) val optionId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val verdict: CompatibilityVerdict,
    @Enumerated(EnumType.STRING) val previousVerdict: CompatibilityVerdict?,
    @Column(nullable = false, length = 500) val reason: String,
    @Column(nullable = false) val createdAt: Instant,
    @Column(nullable = false) val revision: Long,
    @Column(nullable = false) val source: String = "MAKER",
    @Id val id: UUID = UUID.randomUUID(),
    val answerId: UUID? = null,
)

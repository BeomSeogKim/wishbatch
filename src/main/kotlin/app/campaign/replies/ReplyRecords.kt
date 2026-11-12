package app.campaign.replies

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "reply_batches")
class ReplyBatch(
    @Id val runId: UUID,
    @Column(nullable = false) val workspaceId: UUID,
    @Column(nullable = false, length = 64) var evidenceHash: String,
    @Column(nullable = false) var status: String = "GENERATING",
    @Column(nullable = false, columnDefinition = "text") var draftsJson: String = "{}",
    var model: String? = null,
)

@Entity
@Table(name = "reply_approvals")
class ReplyApproval(
    @Id val id: UUID,
    @Column(nullable = false) val workspaceId: UUID,
    @Column(nullable = false) val runId: UUID,
    @Column(nullable = false, length = 64) val groupId: String,
    @Column(nullable = false, length = 64) val evidenceHash: String,
    @Column(nullable = false, length = 500) var body: String,
    @Column(nullable = false) var approvedAt: Instant,
)

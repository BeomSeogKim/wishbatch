package app.campaign.provenance

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.util.UUID

@Entity
@Table(name = "decision_source_snapshots", uniqueConstraints = [UniqueConstraint(columnNames = ["source_type", "source_id"])])
class DecisionSourceSnapshot(
    @Column(name = "workspace_id", nullable = false, updatable = false) val workspaceId: UUID,
    @Column(name = "source_type", nullable = false, updatable = false) val sourceType: String,
    @Column(name = "source_id", nullable = false, updatable = false) val sourceId: UUID,
    @Column(nullable = false, updatable = false, columnDefinition = "text") val payload: String,
    @Column(nullable = false, updatable = false, length = 64) val hash: String,
    @Id val id: UUID = UUID.randomUUID(),
)

@Entity
@Table(name = "pledge_decision_records")
class PledgeDecisionRecord(
    @Id val pledgeId: UUID,
    @Column(nullable = false, updatable = false) val workspaceId: UUID,
    @Column(nullable = false, updatable = false) val campaignId: UUID,
    @Column(nullable = false, updatable = false) val offerId: UUID,
    @Column(nullable = false) var entryCount: Int = 0,
    @Column(length = 64) var lastHash: String? = null,
)

@Entity
@Table(name = "decision_record_entries", uniqueConstraints = [UniqueConstraint(columnNames = ["pledge_id", "position"])])
class DecisionRecordEntry(
    @Column(name = "pledge_id", nullable = false, updatable = false) val pledgeId: UUID,
    @Column(nullable = false, updatable = false) val position: Int,
    @Column(nullable = false, updatable = false) val kind: String,
    @Column(nullable = false, updatable = false, columnDefinition = "text") val payload: String,
    @Column(updatable = false, length = 64) val previousHash: String?,
    @Column(nullable = false, updatable = false, length = 64) val hash: String,
    @Id val id: UUID = UUID.randomUUID(),
)

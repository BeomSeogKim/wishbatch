package app.campaign.analysisrun

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/** Failed provider attempts consume quota without replacing the last successful snapshot. */
@Entity
@Table(name = "analysis_run_quotas")
class AnalysisRunQuota(
    @Id
    val workspaceId: UUID,
    @Column(nullable = false)
    var liveRuns: Long = 0,
)

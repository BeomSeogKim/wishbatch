package app.campaign.progress

import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.port.AnalysisFailure
import app.campaign.rules.OptionWishCounts
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "analysis_progress_jobs")
class AnalysisProgressJob(
    @Column(nullable = false) val workspaceId: UUID,
    @Column(nullable = false) val ownerId: UUID,
    @Column(nullable = false) val inputHash: String,
    @Column(nullable = false, columnDefinition = "text") val sourceWishIdsJson: String,
    @Column(nullable = false) val createdAt: Instant,
    @Column(nullable = false) var status: String = "QUEUED",
    @Column(nullable = false, columnDefinition = "text") var stateJson: String,
    var runId: UUID? = null,
    var failure: String? = null,
    @Id val id: UUID = UUID.randomUUID(),
)

data class ProgressEventView(
    val sequence: Int,
    val at: Instant,
    val stage: String,
    val reading: Int?,
    val batch: Int?,
    val totalBatches: Int?,
    val processedWishes: Int,
    val validationCounts: Map<String, Int>,
)

data class ProgressState(
    val processedWishIds: Set<UUID> = emptySet(),
    val completedBatchKeys: Set<String> = emptySet(),
    val events: List<ProgressEventView> = emptyList(),
    val validationCounts: Map<String, Int> = emptyMap(),
    val finalCounts: List<OptionWishCounts> = emptyList(),
    val checkpointIncomplete: Boolean = false,
)

data class AnalysisProgressView(
    val id: UUID,
    val status: String,
    val totalWishes: Int,
    val processedWishes: Int,
    val completedBatches: Int,
    val inputHash: String,
    val sourceWishIds: List<UUID>,
    val events: List<ProgressEventView>,
    val validationCounts: Map<String, Int>,
    val runId: UUID?,
    val finalCounts: List<OptionWishCounts>,
    val failure: String?,
    val checkpointIncomplete: Boolean = false,
)

sealed interface ProgressStart {
    data class Started(val job: AnalysisProgressView) : ProgressStart
    data class Existing(val job: AnalysisProgressView) : ProgressStart
    data object Busy : ProgressStart
    data object NoVisibleWishes : ProgressStart
    data class Failed(val reason: AnalysisFailure) : ProgressStart
}

data class AnalysisWork(
    val jobId: UUID,
    val workspaceId: UUID,
    val options: List<Option>,
    val wishes: List<Wish>,
)

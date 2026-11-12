package app.campaign.model

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** An immutable analysis snapshot; its JSON is evidence, not authority for prices or counts. */
@Entity
@Table(name = "analysis_runs")
class AnalysisRun(
    @Column(nullable = false)
    val workspaceId: UUID,
    @Column(nullable = false)
    val model: String,
    @Column(nullable = false)
    val inputHash: String,
    @Column(nullable = false, columnDefinition = "text")
    val resultJson: String,
    @Convert(converter = StringListConverter::class)
    @Column(nullable = false, columnDefinition = "text")
    val validationFlags: List<String> = emptyList(),
    @Column(nullable = false)
    val isSeed: Boolean = false,
    @Column(nullable = false)
    val createdAt: Instant,
    @Id
    val id: UUID = UUID.randomUUID(),
)

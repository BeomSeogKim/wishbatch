package app.campaign.model

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/** Maker-confirmed terms and evidence; a published offer is replaced rather than edited. */
@Entity
@Table(name = "offers")
class Offer(
    @Column(nullable = false)
    val workspaceId: UUID,
    @Column(nullable = false)
    val optionId: UUID,
    @Column(nullable = false)
    val analysisRunId: UUID,
    @Column(nullable = false)
    val title: String,
    @Column(nullable = false, columnDefinition = "text")
    val specText: String,
    @Convert(converter = StringMapConverter::class)
    @Column(nullable = false, columnDefinition = "text")
    val chosenVariants: Map<String, String>,
    @Column(nullable = false)
    val unitPrice: Long,
    @Column(nullable = false, length = 3)
    val currency: String,
    @Column(nullable = false, columnDefinition = "text")
    val deliveryTerms: String,
    @Column(nullable = false)
    val shipRegion: String,
    @Column(nullable = false)
    val minBatch: Int,
    @Column(nullable = false)
    val capacity: Int,
    @Column(nullable = false)
    val maxQtyPerPledge: Int = 3,
    @Convert(converter = UuidListConverter::class)
    @Column(nullable = false, columnDefinition = "text")
    val supportingWishIds: List<UUID> = emptyList(),
    @Id
    val id: UUID = UUID.randomUUID(),
)

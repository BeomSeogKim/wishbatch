package app.campaign.model

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/** Feasible product constraints; costs use minor units and each variant can allow several values. */
@Entity
@Table(name = "product_options")
class Option(
    @Column(nullable = false)
    val workspaceId: UUID,
    @Column(nullable = false)
    val name: String,
    @Column(nullable = false)
    val format: String,
    @Convert(converter = StringMapConverter::class)
    @Column(nullable = false, columnDefinition = "text")
    val fixedAttributes: Map<String, String>,
    @Convert(converter = VariantMapConverter::class)
    @Column(nullable = false, columnDefinition = "text")
    val allowedVariants: Map<String, List<String>>,
    @Column(nullable = false)
    val unitCost: Long,
    @Column(nullable = false, length = 3)
    val currency: String,
    @Column(nullable = false)
    val minBatch: Int,
    @Column(nullable = false)
    val maxCapacity: Int,
    @Column(nullable = false, columnDefinition = "text")
    val leadTimeText: String,
    @Column(nullable = false)
    val shipRegion: String,
    @Id
    val id: UUID = UUID.randomUUID(),
)

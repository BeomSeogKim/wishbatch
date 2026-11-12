package app.campaign.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** An append-only diagnostic entry; detail must exclude credentials and unmasked buyer data. */
@Entity
@Table(name = "campaign_events")
class Event(
    @Column(nullable = false)
    val campaignId: UUID,
    @Column(nullable = false)
    val type: String,
    val pledgeId: UUID? = null,
    val paypalEndpoint: String? = null,
    val paypalId: String? = null,
    val paypalStatus: String? = null,
    val debugId: String? = null,
    @Column(columnDefinition = "text")
    val detail: String? = null,
    @Column(nullable = false)
    val createdAt: Instant,
    @Id
    val id: UUID = UUID.randomUUID(),
)

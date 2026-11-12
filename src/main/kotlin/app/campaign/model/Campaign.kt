package app.campaign.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Draft campaigns have no window; publication supplies all three UTC deadlines. Services evaluate
 * deadlines on reads and a scheduled minute tick. Pending captures/refunds and failed voids/refunds
 * set needsAttention without delaying a terminal outcome; late completed captures on NOT_FUNDED
 * campaigns require a refund. Only one nonterminal campaign may exist per workspace.
 */
@Entity
@Table(name = "campaigns")
class Campaign(
    @Column(nullable = false)
    val offerId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var state: CampaignState = CampaignState.DRAFT,
    @Enumerated(EnumType.STRING)
    var reason: CampaignReason? = null,
    var openedAt: Instant? = null,
    var closesAt: Instant? = null,
    var confirmDeadline: Instant? = null,
    @Column(nullable = false)
    var needsAttention: Boolean = false,
    @Id
    val id: UUID = UUID.randomUUID(),
)

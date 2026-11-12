package app.campaign.model

import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Amount is server-computed in minor units; the ID exists before persistence for payment request keys. */
@Entity
@Table(name = "pledges")
class Pledge(
    @Column(nullable = false)
    val campaignId: UUID,
    @Column(nullable = false)
    val qty: Int,
    @Column(nullable = false)
    val amount: Long,
    @Column(nullable = false, length = 3)
    val currency: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var state: PledgeState = PledgeState.CREATED,
    var paypalOrderId: String? = null,
    var authorizationId: String? = null,
    var authorizationExpiresAt: Instant? = null,
    var captureId: String? = null,
    var refundId: String? = null,
    var payerEmailMasked: String? = null,
    @Embedded
    var shippingAddress: ShippingAddress? = null,
    @Column(columnDefinition = "text")
    var lastError: String? = null,
    @Column(nullable = false)
    val createdAt: Instant,
    @Id
    val id: UUID = UUID.randomUUID(),
    val wishId: UUID? = null,
)

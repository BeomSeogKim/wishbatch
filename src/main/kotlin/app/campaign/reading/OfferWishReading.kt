package app.campaign.reading

import app.campaign.model.Offer
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.provenance.DecisionRecordHash
import app.campaign.rules.analysisInputHash
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "offer_wish_readings", uniqueConstraints = [UniqueConstraint(columnNames = ["offer_id", "wish_id"])])
class OfferWishReading(
    @Column(nullable = false, updatable = false) val workspaceId: UUID,
    @Column(nullable = false, updatable = false) val offerId: UUID,
    @Column(nullable = false, updatable = false) val wishId: UUID,
    @Column(nullable = false, updatable = false) val inputHash: String,
    @Column(nullable = false, updatable = false, columnDefinition = "text") val inputJson: String,
    @Column(nullable = false) var attempts: Int = 0,
    var reservedUntil: Instant? = null,
    var sourceId: UUID? = null,
    @Column(nullable = false) var completed: Boolean = false,
    @Id val id: UUID = UUID.randomUUID(),
)

/** The model sees catalog format and exact wish text; the scope also binds the published offer seal. */
fun readingInput(hashing: DecisionRecordHash, option: Option, wish: Wish, offer: Offer, termsHash: String): String =
    hashing.canonical(mapOf(
        "version" to 1, "workspaceId" to offer.workspaceId, "offerId" to offer.id,
        "optionId" to option.id, "wishId" to wish.id, "wishText" to wish.text,
        "termsHash" to termsHash, "option" to option,
        "classifierInputHash" to analysisInputHash(listOf(option), listOf(wish)),
    ))

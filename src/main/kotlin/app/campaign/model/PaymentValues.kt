package app.campaign.model

import jakarta.persistence.Embeddable

/** An exact amount in the currency's smallest unit; no decimal conversion is implied. */
data class Money(val minorUnits: Long, val currency: String)

/** Missing provider fields remain null rather than being inferred from other address fields. */
@Embeddable
data class ShippingAddress(
    val fullName: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val adminArea1: String? = null,
    val adminArea2: String? = null,
    val postalCode: String? = null,
    val countryCode: String? = null,
)

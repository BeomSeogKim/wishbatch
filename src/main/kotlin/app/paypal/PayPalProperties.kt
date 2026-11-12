package app.paypal

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class PayPalProperties(
    @Value("\${PAYPAL_BASE_URL:https://api-m.sandbox.paypal.com}") val baseUrl: String,
    @Value("\${PAYPAL_CLIENT_ID:}") internal val clientId: String,
    @Value("\${PAYPAL_CLIENT_SECRET:}") internal val clientSecret: String,
) {
    init {
        require(baseUrl == SANDBOX_URL) {
            "PAYPAL_BASE_URL must be exactly $SANDBOX_URL; only sandbox payments are allowed"
        }
    }

    internal val configured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    companion object {
        const val SANDBOX_URL = "https://api-m.sandbox.paypal.com"
    }
}

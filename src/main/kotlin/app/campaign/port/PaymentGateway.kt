package app.campaign.port

import app.campaign.model.Money
import app.campaign.model.ShippingAddress
import java.time.Instant
import java.util.UUID

@JvmInline value class OrderId(val value: String)
@JvmInline value class AuthorizationId(val value: String)
@JvmInline value class CaptureId(val value: String)
@JvmInline value class RefundId(val value: String)

/** Status belongs to the returned resource: an order COMPLETED is not a completed capture. */
enum class PaymentStatus {
    CREATED, APPROVED, COMPLETED, PENDING, DECLINED, CAPTURED, REFUNDED, PARTIALLY_REFUNDED,
    VOIDED, EXPIRED, ALREADY_CAPTURED, FAILED, TRANSPORT_FAILURE,
}

/**
 * Expected provider outcomes never throw. Received means a resource was returned, not that money
 * moved. Failed has no resource; errorCode preserves a provider issue code without parsing prose.
 * A transport failure is ambiguous: the provider may already have applied the operation.
 */
sealed interface PaymentResult<out T> {
    val status: PaymentStatus
    val debugId: String?

    data class Received<T>(
        val resource: T,
        override val status: PaymentStatus,
        override val debugId: String? = null,
    ) : PaymentResult<T>

    data class Failed(
        override val status: PaymentStatus,
        override val debugId: String? = null,
        val errorCode: String? = null,
        val message: String? = null,
    ) : PaymentResult<Nothing>
}

/** Order reads expose payment references without buyer data; amount requires one purchase unit. */
data class OrderDetails(
    val orderId: OrderId,
    val providerStatus: String? = null,
    val authorizationIds: List<AuthorizationId> = emptyList(),
    val captureIds: List<CaptureId> = emptyList(),
    val refundIds: List<RefundId> = emptyList(),
    val amount: Money? = null,
)

/**
 * Amount and currency must match the server's pledge before accepting the hold. captureId is
 * optional reconciliation evidence, never inferred from an authorization ID or status.
 */
data class AuthorizationDetails(
    val authorizationId: AuthorizationId,
    val amount: Money,
    val expiresAt: Instant?,
    val orderId: OrderId? = null,
    val payerEmail: String? = null,
    val shippingAddress: ShippingAddress? = null,
    val captureId: CaptureId? = null,
)

/**
 * A partially refunded capture retains PARTIALLY_REFUNDED status and its original amount.
 * That amount does not identify the retained balance.
 */
data class CaptureDetails(
    val captureId: CaptureId,
    val amount: Money,
    val authorizationId: AuthorizationId? = null,
    val payerEmail: String? = null,
    val shippingAddress: ShippingAddress? = null,
)

data class VoidDetails(val authorizationId: AuthorizationId)

data class RefundDetails(
    val refundId: RefundId,
    val amount: Money,
    val captureId: CaptureId? = null,
)

/** Exact sealed offer terms and server pledge values; pledgeId is the provider's custom reference. */
data class CreateAuthorizeOrder(
    val pledgeId: UUID,
    val amount: Money,
    val offerTitle: String,
    val quantity: Int,
    val unitAmount: Money,
    val termsHash: String,
)

enum class WebhookVerification { VERIFIED, INVALID, UNAVAILABLE }

/**
 * Sandbox payment boundary. Every call accepts the same deterministic key on replay; reads accept
 * a key for tracing even if the provider does not send it as a header. Implementations request
 * shipping on AUTHORIZE orders, use final_capture=true, and perform full captures/refunds.
 * Authorize returns the authorization's status (normally CREATED), not its parent order's status.
 * Missing provider fields stay null. Raw payer data must not enter logs or public event details.
 * Timeouts/5xx allow at most three retries, always with the original key. Terminal conflicts
 * (ALREADY_CAPTURED, VOIDED, EXPIRED) require readAuthorization before local state is reconciled.
 * An authorization reported CAPTURED does not prove its capture COMPLETED. Resolve the capture
 * through its known ID or a replay with the original capture key, then inspect its own status.
 * If the ID cannot be recovered, leave payment completion unconfirmed for manual reconciliation.
 */
interface PaymentGateway {
    fun createAuthorizeOrder(request: CreateAuthorizeOrder, idempotencyKey: String): PaymentResult<OrderDetails>
    fun readOrder(orderId: OrderId, idempotencyKey: String): PaymentResult<OrderDetails> =
        PaymentResult.Failed(PaymentStatus.FAILED, errorCode = "UNSUPPORTED_OPERATION")
    fun authorizeOrder(orderId: OrderId, idempotencyKey: String): PaymentResult<AuthorizationDetails>
    fun readAuthorization(authorizationId: AuthorizationId, idempotencyKey: String): PaymentResult<AuthorizationDetails>
    fun capture(authorizationId: AuthorizationId, idempotencyKey: String): PaymentResult<CaptureDetails>
    fun voidAuthorization(authorizationId: AuthorizationId, idempotencyKey: String): PaymentResult<VoidDetails>
    fun refundCapture(captureId: CaptureId, idempotencyKey: String): PaymentResult<RefundDetails>
    fun readCapture(captureId: CaptureId, idempotencyKey: String): PaymentResult<CaptureDetails>
    fun readRefund(refundId: RefundId, idempotencyKey: String): PaymentResult<RefundDetails>

    /** Verify the complete event using lowercase transmission-header names before processing it. */
    fun verifyWebhook(webhookId: String, headers: Map<String, String>, eventJson: String): WebhookVerification =
        WebhookVerification.UNAVAILABLE
}

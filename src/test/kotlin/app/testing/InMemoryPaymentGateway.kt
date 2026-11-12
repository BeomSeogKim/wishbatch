package app.testing

import app.campaign.model.Money
import app.campaign.model.Pledge
import app.campaign.port.AuthorizationDetails
import app.campaign.port.AuthorizationId
import app.campaign.port.CaptureDetails
import app.campaign.port.CaptureId
import app.campaign.port.CreateAuthorizeOrder
import app.campaign.port.OrderDetails
import app.campaign.port.OrderId
import app.campaign.port.PaymentGateway
import app.campaign.port.PaymentResult
import app.campaign.port.PaymentStatus
import app.campaign.port.RefundDetails
import app.campaign.port.RefundId
import app.campaign.port.VoidDetails

sealed interface PaymentCall {
    val idempotencyKey: String

    data class CreateOrder(val request: CreateAuthorizeOrder, override val idempotencyKey: String) : PaymentCall
    data class AuthorizeOrder(val orderId: OrderId, override val idempotencyKey: String) : PaymentCall
    data class ReadAuthorization(val authorizationId: AuthorizationId, override val idempotencyKey: String) : PaymentCall
    data class Capture(val authorizationId: AuthorizationId, override val idempotencyKey: String) : PaymentCall
    data class VoidAuthorization(val authorizationId: AuthorizationId, override val idempotencyKey: String) : PaymentCall
    data class RefundCapture(val captureId: CaptureId, override val idempotencyKey: String) : PaymentCall
    data class ReadCapture(val captureId: CaptureId, override val idempotencyKey: String) : PaymentCall
    data class ReadRefund(val refundId: RefundId, override val idempotencyKey: String) : PaymentCall
}

/**
 * Add results to each operation's FIFO queue before calling it. Order reads may reuse explicit
 * registered or create-request facts and have a separate trace. Other unscripted calls fail.
 * After N calls, failAfterCalls returns failure for every operation without consuming a script.
 * All attempts are recorded, including repeated keys; this fake does not deduplicate requests.
 */
class InMemoryPaymentGateway(
    private val failAfterCalls: Int? = null,
    private val failure: PaymentResult.Failed = PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE),
) : PaymentGateway {
    data class OrderRead(val orderId: OrderId, val idempotencyKey: String)
    init {
        require(failAfterCalls == null || failAfterCalls >= 0) { "failAfterCalls must be non-negative" }
    }

    val createOrderResults = ArrayDeque<PaymentResult<OrderDetails>>()
    val readOrderResults = ArrayDeque<PaymentResult<OrderDetails>>()
    val orderReads = mutableListOf<OrderRead>()
    private val orders = mutableMapOf<OrderId, OrderDetails>()
    val authorizeOrderResults = ArrayDeque<PaymentResult<AuthorizationDetails>>()
    val readAuthorizationResults = ArrayDeque<PaymentResult<AuthorizationDetails>>()
    val captureResults = ArrayDeque<PaymentResult<CaptureDetails>>()
    val voidAuthorizationResults = ArrayDeque<PaymentResult<VoidDetails>>()
    val refundCaptureResults = ArrayDeque<PaymentResult<RefundDetails>>()
    val readCaptureResults = ArrayDeque<PaymentResult<CaptureDetails>>()
    val readRefundResults = ArrayDeque<PaymentResult<RefundDetails>>()

    private val recordedCalls = mutableListOf<PaymentCall>()
    val calls: List<PaymentCall> get() = recordedCalls.toList()

    override fun createAuthorizeOrder(request: CreateAuthorizeOrder, idempotencyKey: String): PaymentResult<OrderDetails> {
        val result = next(PaymentCall.CreateOrder(request, idempotencyKey), createOrderResults)
        if (result is PaymentResult.Received) orders[result.resource.orderId] = result.resource.copy(amount = request.amount)
        return result
    }

    fun registerOrder(pledge: Pledge) {
        val id = OrderId(requireNotNull(pledge.paypalOrderId))
        orders[id] = OrderDetails(id, amount = Money(pledge.amount, pledge.currency))
    }

    override fun readOrder(orderId: OrderId, idempotencyKey: String): PaymentResult<OrderDetails> {
        orderReads.add(OrderRead(orderId, idempotencyKey))
        if (failAfterCalls != null && recordedCalls.size + orderReads.size > failAfterCalls) return failure
        return readOrderResults.removeFirstOrNull() ?: PaymentResult.Received(
            orders[orderId] ?: throw AssertionError("No scripted order evidence for $orderId"), PaymentStatus.APPROVED,
        )
    }

    override fun authorizeOrder(orderId: OrderId, idempotencyKey: String): PaymentResult<AuthorizationDetails> =
        next(PaymentCall.AuthorizeOrder(orderId, idempotencyKey), authorizeOrderResults)

    override fun readAuthorization(authorizationId: AuthorizationId, idempotencyKey: String): PaymentResult<AuthorizationDetails> =
        next(PaymentCall.ReadAuthorization(authorizationId, idempotencyKey), readAuthorizationResults)

    override fun capture(authorizationId: AuthorizationId, idempotencyKey: String): PaymentResult<CaptureDetails> =
        next(PaymentCall.Capture(authorizationId, idempotencyKey), captureResults)

    override fun voidAuthorization(authorizationId: AuthorizationId, idempotencyKey: String): PaymentResult<VoidDetails> =
        next(PaymentCall.VoidAuthorization(authorizationId, idempotencyKey), voidAuthorizationResults)

    override fun refundCapture(captureId: CaptureId, idempotencyKey: String): PaymentResult<RefundDetails> =
        next(PaymentCall.RefundCapture(captureId, idempotencyKey), refundCaptureResults)

    override fun readCapture(captureId: CaptureId, idempotencyKey: String): PaymentResult<CaptureDetails> =
        next(PaymentCall.ReadCapture(captureId, idempotencyKey), readCaptureResults)

    override fun readRefund(refundId: RefundId, idempotencyKey: String): PaymentResult<RefundDetails> =
        next(PaymentCall.ReadRefund(refundId, idempotencyKey), readRefundResults)

    private fun <T> next(call: PaymentCall, results: ArrayDeque<PaymentResult<T>>): PaymentResult<T> {
        recordedCalls.add(call)
        if (failAfterCalls != null && recordedCalls.size + orderReads.size > failAfterCalls) return failure
        return results.removeFirstOrNull()
            ?: throw AssertionError("No scripted payment result for ${call.javaClass.simpleName}")
    }
}

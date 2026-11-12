package app.campaign.fuzz

import app.campaign.model.Money
import app.campaign.port.*
import app.testing.InMemoryPaymentGateway
import app.testing.PaymentCall
import java.time.Instant

class SequenceCrash : RuntimeException()

class SequenceGateway : PaymentGateway {
    private var fake = InMemoryPaymentGateway()
    private val amounts = mutableMapOf<String, Money>()
    private val captures = mutableMapOf<String, CaptureDetails>()
    private val refunds = mutableMapOf<String, RefundDetails>()
    private val captureKeys = mutableMapOf<String, String>()
    private val refundKeys = mutableMapOf<String, String>()
    private val completedCaptures = mutableSetOf<String>()
    private val completedRefunds = mutableSetOf<String>()
    val paidCaptures: Set<String> get() = completedCaptures - completedRefunds
    var captureStatus = PaymentStatus.COMPLETED
    var cleanupFails = false
    var crashCapture = false
    var pendingCompletes = false
    val calls: List<PaymentCall> get() = fake.calls

    fun reset() {
        fake = InMemoryPaymentGateway()
        amounts.clear(); captures.clear(); refunds.clear(); captureKeys.clear(); refundKeys.clear()
        completedCaptures.clear(); completedRefunds.clear()
        captureStatus = PaymentStatus.COMPLETED
        cleanupFails = false; crashCapture = false; pendingCompletes = false
    }

    override fun createAuthorizeOrder(request: CreateAuthorizeOrder, idempotencyKey: String): PaymentResult<OrderDetails> {
        val id = "order-${request.pledgeId}"
        amounts[id] = request.amount
        fake.createOrderResults.add(PaymentResult.Received(OrderDetails(OrderId(id)), PaymentStatus.CREATED))
        return fake.createAuthorizeOrder(request, idempotencyKey)
    }

    override fun authorizeOrder(orderId: OrderId, idempotencyKey: String): PaymentResult<AuthorizationDetails> {
        val id = AuthorizationId(orderId.value.replaceFirst("order-", "auth-"))
        amounts[id.value] = amounts.getValue(orderId.value)
        fake.authorizeOrderResults.add(PaymentResult.Received(AuthorizationDetails(id, amounts.getValue(id.value),
            Instant.parse("2026-01-02T00:00:00Z"), orderId), PaymentStatus.CREATED))
        return fake.authorizeOrder(orderId, idempotencyKey)
    }

    override fun readAuthorization(authorizationId: AuthorizationId, idempotencyKey: String): PaymentResult<AuthorizationDetails> {
        val capture = captures[authorizationId.value]
        fake.readAuthorizationResults.add(PaymentResult.Received(AuthorizationDetails(authorizationId,
            amounts.getValue(authorizationId.value), null, captureId = capture?.captureId),
            if (capture == null) PaymentStatus.CREATED else PaymentStatus.CAPTURED))
        return fake.readAuthorization(authorizationId, idempotencyKey)
    }

    override fun capture(authorizationId: AuthorizationId, idempotencyKey: String): PaymentResult<CaptureDetails> {
        val oldKey = captureKeys.putIfAbsent(authorizationId.value, idempotencyKey)
        check(oldKey == null || oldKey == idempotencyKey)
        val result = if (captureStatus == PaymentStatus.DECLINED) PaymentResult.Failed(PaymentStatus.DECLINED) else {
            val details = captures.getOrPut(authorizationId.value) {
                CaptureDetails(CaptureId("capture-${authorizationId.value}"), amounts.getValue(authorizationId.value), authorizationId)
            }
            PaymentResult.Received(details, captureStatus)
        }
        if (result is PaymentResult.Received && result.status == PaymentStatus.COMPLETED) completedCaptures.add(result.resource.captureId.value)
        fake.captureResults.add(result)
        val response = fake.capture(authorizationId, idempotencyKey)
        if (crashCapture && result is PaymentResult.Received) { crashCapture = false; throw SequenceCrash() }
        return response
    }

    override fun voidAuthorization(authorizationId: AuthorizationId, idempotencyKey: String): PaymentResult<VoidDetails> {
        fake.voidAuthorizationResults.add(if (cleanupFails) PaymentResult.Failed(PaymentStatus.FAILED)
            else PaymentResult.Received(VoidDetails(authorizationId), PaymentStatus.VOIDED))
        return fake.voidAuthorization(authorizationId, idempotencyKey)
    }

    override fun refundCapture(captureId: CaptureId, idempotencyKey: String): PaymentResult<RefundDetails> {
        val oldKey = refundKeys.putIfAbsent(captureId.value, idempotencyKey)
        check(oldKey == null || oldKey == idempotencyKey)
        val capture = captures.values.single { it.captureId == captureId }
        val result = if (cleanupFails) PaymentResult.Failed(PaymentStatus.FAILED) else {
            val refund = refunds.getOrPut(captureId.value) { RefundDetails(RefundId("refund-${captureId.value}"), capture.amount, captureId) }
            PaymentResult.Received(refund, PaymentStatus.COMPLETED)
        }
        if (result is PaymentResult.Received) completedRefunds.add(captureId.value)
        fake.refundCaptureResults.add(result)
        return fake.refundCapture(captureId, idempotencyKey)
    }

    override fun readCapture(captureId: CaptureId, idempotencyKey: String): PaymentResult<CaptureDetails> {
        val capture = captures.values.single { it.captureId == captureId }
        if (pendingCompletes) completedCaptures.add(captureId.value)
        fake.readCaptureResults.add(PaymentResult.Received(capture,
            if (pendingCompletes) PaymentStatus.COMPLETED else captureStatus))
        return fake.readCapture(captureId, idempotencyKey)
    }

    override fun readRefund(refundId: RefundId, idempotencyKey: String): PaymentResult<RefundDetails> {
        fake.readRefundResults.add(PaymentResult.Received(refunds.values.single { it.refundId == refundId }, PaymentStatus.COMPLETED))
        return fake.readRefund(refundId, idempotencyKey)
    }
}

package app.campaign.service

import app.campaign.model.Event
import app.campaign.model.Money
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
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
import app.campaign.rules.PaymentAction
import app.campaign.rules.idempotencyKey
import app.campaign.rules.requirePledgeTransition
import app.campaign.store.EventRepository
import app.campaign.store.PledgeRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class CampaignPayments(
    private val gateways: ObjectProvider<PaymentGateway>,
    private val persistence: CampaignPersistence,
    private val pledges: PledgeRepository,
    private val events: EventRepository,
    private val clock: Clock,
) {
    fun save(pledge: Pledge) = persistence.checkpoint { pledges.saveAndFlush(pledge) }

    fun move(pledge: Pledge, state: PledgeState) {
        if (pledge.state == state) {
            save(pledge)
            return
        }
        val previous = pledge.state
        pledge.state = requirePledgeTransition(previous, state)
        persistence.checkpoint {
            pledges.saveAndFlush(pledge)
            events.save(Event(pledge.campaignId, "PLEDGE_STATE", pledge.id,
                detail = "$previous -> $state", createdAt = clock.instant()))
        }
    }

    fun attempted(pledge: Pledge, action: PaymentAction): Boolean = persistence.checkpoint {
        events.findByCampaignIdOrderByCreatedAtAscIdAsc(pledge.campaignId)
            .any { it.pledgeId == pledge.id && it.type == "${action.name}_REQUEST" }
    }

    fun createOrder(pledge: Pledge): PaymentResult<OrderDetails> = call(
        pledge, PaymentAction.CREATE_ORDER, "POST /v2/checkout/orders", pledge.paypalOrderId,
    ) { gateway, key -> gateway.createAuthorizeOrder(CreateAuthorizeOrder(pledge.id, money(pledge)), key) }

    fun authorize(pledge: Pledge): PaymentResult<AuthorizationDetails> = call(
        pledge, PaymentAction.AUTHORIZE, "POST /v2/checkout/orders/${pledge.paypalOrderId}/authorize", pledge.paypalOrderId,
    ) { gateway, key -> gateway.authorizeOrder(OrderId(requireNotNull(pledge.paypalOrderId)), key) }

    fun readAuthorization(pledge: Pledge): PaymentResult<AuthorizationDetails> = call(
        pledge, PaymentAction.READ_AUTHORIZATION,
        "GET /v2/payments/authorizations/${pledge.authorizationId}", pledge.authorizationId,
    ) { gateway, key -> gateway.readAuthorization(AuthorizationId(requireNotNull(pledge.authorizationId)), key) }

    fun capture(pledge: Pledge, simulatedFailure: Boolean = false, allowNewCapture: Boolean = true) {
        if (simulatedFailure && !attempted(pledge, PaymentAction.CAPTURE)) {
            event(pledge, "SIMULATED_CAPTURE_FAILURE", detail = "Demo simulation; no capture request sent.")
            pledge.lastError = "SIMULATED_CAPTURE_FAILURE"
            move(pledge, PledgeState.CAPTURE_FAILED)
            return
        }
        if (attempted(pledge, PaymentAction.CAPTURE)) {
            recoverCapture(pledge, allowNewCapture)
            return
        }
        val result = captureRequest(pledge)
        if (result.status in ambiguousOrConflict || transportFailureObserved(pledge, PaymentAction.CAPTURE)) recoverCapture(pledge, allowNewCapture)
        else applyCapture(pledge, result)
    }

    fun refreshCapture(pledge: Pledge, allowNewCapture: Boolean = false) {
        if (pledge.captureId != null) applyCapture(pledge, readCapture(pledge), uncertainFailure = true)
        else recoverCapture(pledge, allowNewCapture)
    }

    private fun recoverCapture(pledge: Pledge, allowNewCapture: Boolean) {
        val authorization = readAuthorization(pledge)
        if (authorization is PaymentResult.Received && authorization.resource.authorizationId.value == pledge.authorizationId) {
            if (authorization.status in setOf(PaymentStatus.VOIDED, PaymentStatus.EXPIRED)) {
                if (pledge.state == PledgeState.CAPTURE_PENDING) move(pledge, PledgeState.CAPTURE_FAILED)
                pledge.lastError = null
                move(pledge, PledgeState.VOIDED)
                return
            }
            authorization.resource.captureId?.let { pledge.captureId = it.value; save(pledge) }
        }
        if (!allowNewCapture && pledge.captureId == null &&
            authorization.status !in setOf(PaymentStatus.CAPTURED, PaymentStatus.ALREADY_CAPTURED)) {
            pledge.lastError = "CAPTURE_UNCONFIRMED"
            if (authorization is PaymentResult.Received && authorization.status == PaymentStatus.CREATED &&
                authorization.resource.authorizationId.value == pledge.authorizationId) captureFailed(pledge)
            else move(pledge, PledgeState.CAPTURE_PENDING)
            return
        }
        // A replay has the original full-capture key, including when a response or local commit was lost.
        val result = if (pledge.captureId != null) readCapture(pledge) else captureRequest(pledge)
        applyCapture(pledge, result, uncertainFailure = pledge.captureId != null ||
            authorization.status in setOf(PaymentStatus.CAPTURED, PaymentStatus.ALREADY_CAPTURED) ||
            authorization !is PaymentResult.Received)
    }

    private fun captureRequest(pledge: Pledge): PaymentResult<CaptureDetails> = call(
        pledge, PaymentAction.CAPTURE, "POST /v2/payments/authorizations/${pledge.authorizationId}/capture", pledge.authorizationId,
    ) { gateway, key -> gateway.capture(AuthorizationId(requireNotNull(pledge.authorizationId)), key) }

    private fun readCapture(pledge: Pledge): PaymentResult<CaptureDetails> = call(
        pledge, PaymentAction.READ_CAPTURE, "GET /v2/payments/captures/${pledge.captureId}", pledge.captureId,
    ) { gateway, key -> gateway.readCapture(CaptureId(requireNotNull(pledge.captureId)), key) }

    private fun applyCapture(pledge: Pledge, result: PaymentResult<CaptureDetails>, uncertainFailure: Boolean = false) {
        if (result is PaymentResult.Received) {
            val resource = result.resource
            if (resource.amount != money(pledge) ||
                (resource.authorizationId != null && resource.authorizationId.value != pledge.authorizationId) ||
                (pledge.captureId != null && resource.captureId.value != pledge.captureId)
            ) {
                pledge.lastError = "CAPTURE_EVIDENCE_MISMATCH"
                move(pledge, PledgeState.CAPTURE_PENDING)
                return
            }
            pledge.captureId = resource.captureId.value
            when (result.status) {
                PaymentStatus.COMPLETED -> {
                    pledge.lastError = null
                    move(pledge, PledgeState.CAPTURED)
                }
                PaymentStatus.REFUNDED -> {
                    pledge.lastError = null
                    move(pledge, PledgeState.CAPTURED)
                    move(pledge, PledgeState.REFUNDED)
                }
                PaymentStatus.DECLINED, PaymentStatus.FAILED -> {
                    pledge.lastError = result.status.name
                    captureFailed(pledge)
                }
                else -> {
                    pledge.lastError = "CAPTURE_UNCONFIRMED"
                    move(pledge, PledgeState.CAPTURE_PENDING)
                }
            }
        } else {
            pledge.lastError = result.status.name
            if (!uncertainFailure && result.status in setOf(PaymentStatus.DECLINED, PaymentStatus.FAILED)) {
                captureFailed(pledge)
            } else move(pledge, PledgeState.CAPTURE_PENDING)
        }
    }

    private fun captureFailed(pledge: Pledge) {
        if (pledge.state == PledgeState.VOID_FAILED) save(pledge)
        else move(pledge, PledgeState.CAPTURE_FAILED)
    }

    fun transportFailureObserved(pledge: Pledge, action: PaymentAction): Boolean = persistence.checkpoint {
        events.findByCampaignIdOrderByCreatedAtAscIdAsc(pledge.campaignId).any {
            it.pledgeId == pledge.id && it.type == "${action.name}_RESPONSE" && it.paypalStatus == PaymentStatus.TRANSPORT_FAILURE.name
        }
    }

    fun void(pledge: Pledge) {
        if (pledge.authorizationId == null) return
        val result = call(pledge, PaymentAction.VOID,
            "POST /v2/payments/authorizations/${pledge.authorizationId}/void", pledge.authorizationId,
        ) { gateway, key -> gateway.voidAuthorization(AuthorizationId(pledge.authorizationId!!), key) }
        if (result is PaymentResult.Received &&
            result.resource.authorizationId.value == pledge.authorizationId &&
            result.status in setOf(PaymentStatus.VOIDED, PaymentStatus.COMPLETED)
        ) {
            move(pledge, PledgeState.VOIDED)
            return
        }
        pledge.lastError = "VOID_UNCONFIRMED"
        move(pledge, PledgeState.VOID_FAILED)
        if (result.status in ambiguousOrConflict) {
            val authorization = readAuthorization(pledge)
            if (authorization is PaymentResult.Received && authorization.resource.authorizationId.value == pledge.authorizationId) {
                when (authorization.status) {
                    PaymentStatus.VOIDED, PaymentStatus.EXPIRED -> {
                        pledge.lastError = null
                        move(pledge, PledgeState.VOIDED)
                    }
                    PaymentStatus.CAPTURED, PaymentStatus.ALREADY_CAPTURED -> {
                        authorization.resource.captureId?.let { pledge.captureId = it.value; save(pledge) }
                        applyCapture(pledge, if (pledge.captureId != null) readCapture(pledge) else captureRequest(pledge), uncertainFailure = true)
                    }
                    else -> if (authorization.resource.captureId != null) {
                        pledge.captureId = authorization.resource.captureId.value
                        save(pledge)
                        applyCapture(pledge, readCapture(pledge), uncertainFailure = true)
                    }
                }
            }
        }
    }

    fun refund(pledge: Pledge) {
        var result = if (pledge.refundId != null) {
            call(pledge, PaymentAction.READ_REFUND, "GET /v2/payments/refunds/${pledge.refundId}", pledge.refundId) { gateway, key ->
                gateway.readRefund(RefundId(pledge.refundId!!), key)
            }
        } else refundRequest(pledge)
        if (pledge.refundId != null && result is PaymentResult.Received &&
            result.status in setOf(PaymentStatus.FAILED, PaymentStatus.DECLINED)) {
            result = refundRequest(pledge)
        }
        applyRefund(pledge, result)
    }

    private fun refundRequest(pledge: Pledge): PaymentResult<RefundDetails> = call(
        pledge, PaymentAction.REFUND, "POST /v2/payments/captures/${pledge.captureId}/refund", pledge.captureId,
    ) { gateway, key -> gateway.refundCapture(CaptureId(requireNotNull(pledge.captureId)), key) }

    private fun applyRefund(pledge: Pledge, result: PaymentResult<RefundDetails>) {
        if (result is PaymentResult.Received && result.resource.amount == money(pledge) &&
            (result.resource.captureId == null || result.resource.captureId.value == pledge.captureId) &&
            (pledge.refundId == null || result.resource.refundId.value == pledge.refundId)
        ) {
            pledge.refundId = result.resource.refundId.value
            pledge.lastError = if (result.status == PaymentStatus.COMPLETED) null else "REFUND_UNCONFIRMED"
            move(pledge, when (result.status) {
                PaymentStatus.COMPLETED -> PledgeState.REFUNDED
                PaymentStatus.PENDING -> PledgeState.REFUND_PENDING
                else -> PledgeState.REFUND_FAILED
            })
        } else {
            pledge.lastError = "REFUND_UNCONFIRMED"
            move(pledge, if (result.status == PaymentStatus.TRANSPORT_FAILURE) PledgeState.REFUND_PENDING else PledgeState.REFUND_FAILED)
        }
    }

    private fun <T> call(
        pledge: Pledge,
        action: PaymentAction,
        endpoint: String,
        providerId: String?,
        operation: (PaymentGateway, String) -> PaymentResult<T>,
    ): PaymentResult<T> {
        var result: PaymentResult<T>
        var retries = 0
        do {
            event(pledge, "${action.name}_REQUEST", endpoint, providerId, "REQUESTED")
            result = try {
                gateways.ifAvailable?.let { operation(it, idempotencyKey(pledge.id, action)) }
                    ?: PaymentResult.Failed(PaymentStatus.FAILED, errorCode = "NOT_CONFIGURED")
            } catch (exception: Exception) {
                event(pledge, "${action.name}_INTERRUPTED", endpoint, providerId, "UNKNOWN")
                throw exception
            }
            val resourceId = if (result is PaymentResult.Received) when (val resource = result.resource) {
                is OrderDetails -> resource.orderId.value
                is AuthorizationDetails -> resource.authorizationId.value
                is CaptureDetails -> resource.captureId.value
                is RefundDetails -> resource.refundId.value
                is VoidDetails -> resource.authorizationId.value
                else -> providerId
            } else providerId
            event(pledge, "${action.name}_RESPONSE", endpoint, resourceId, result.status.name, result.debugId)
        } while (result.status == PaymentStatus.TRANSPORT_FAILURE && retries++ < 3)
        return result
    }

    private fun event(
        pledge: Pledge, type: String, endpoint: String? = null, providerId: String? = null,
        status: String? = null, debugId: String? = null, detail: String? = null,
    ) = persistence.checkpoint {
        events.saveAndFlush(Event(pledge.campaignId, type, pledge.id, endpoint, providerId, status, debugId, detail, clock.instant()))
    }

    private fun money(pledge: Pledge) = Money(pledge.amount, pledge.currency)

    private val ambiguousOrConflict = setOf(
        PaymentStatus.TRANSPORT_FAILURE, PaymentStatus.ALREADY_CAPTURED, PaymentStatus.CAPTURED,
        PaymentStatus.VOIDED, PaymentStatus.EXPIRED,
    )
}

package app.campaign.rules

import java.nio.charset.StandardCharsets
import java.util.UUID

enum class PaymentAction(val keyName: String) {
    CREATE_ORDER("create-order"), AUTHORIZE("authorize"), READ_AUTHORIZATION("read-authorization"),
    CAPTURE("capture"), VOID("void"), REFUND("refund"), READ_CAPTURE("read-capture"), READ_REFUND("read-refund"),
}

/**
 * Stable 36-character request ID for one full operation per pledge. Retries and process restarts
 * reuse it. This namespace and action names are persisted protocol: changing them can duplicate
 * payments. Partial captures/refunds and replacement orders require a different contract.
 */
fun idempotencyKey(pledgeId: UUID, action: PaymentAction): String =
    UUID.nameUUIDFromBytes("wishbatch:$pledgeId:${action.keyName}".toByteArray(StandardCharsets.UTF_8)).toString()

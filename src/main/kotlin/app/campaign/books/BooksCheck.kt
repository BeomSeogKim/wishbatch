package app.campaign.books

import app.campaign.model.PledgeState
import app.campaign.port.PaymentStatus
import java.time.Instant
import java.util.UUID

enum class BooksMatch { MATCH, MISMATCH, UNKNOWN }
enum class BooksResourceKind { ORDER, AUTHORIZATION, CAPTURE, REFUND }

data class BooksResource(
    val kind: BooksResourceKind,
    val id: String,
    val status: PaymentStatus?,
    val providerStatus: String?,
    val amount: Long?,
    val currency: String?,
    val evidenceMatches: Boolean,
)

data class BooksPledgeSnapshot(
    val state: PledgeState,
    val resources: List<BooksResource>,
    val match: BooksMatch,
)

data class BooksPledgeRow(
    val pledgeId: UUID,
    val quantity: Int,
    val amount: Long,
    val currency: String,
    val before: BooksPledgeSnapshot,
    val after: BooksPledgeSnapshot,
)

/** A dated observation, not a promise that provider state will remain unchanged. */
data class BooksCheck(
    val id: UUID,
    val campaignId: UUID,
    val startedAt: Instant,
    val checkedAt: Instant,
    val pledges: List<BooksPledgeRow>,
    val version: Int = 1,
)

internal fun booksMatch(state: PledgeState, resources: List<BooksResource>): BooksMatch {
    if (resources.isEmpty() || resources.any { it.status == null || it.amount == null || it.currency == null }) return BooksMatch.UNKNOWN
    if (resources.any { !it.evidenceMatches }) return BooksMatch.MISMATCH
    val kinds = resources.map { it.kind }.toSet()
    if (BooksResourceKind.ORDER !in kinds) return BooksMatch.UNKNOWN
    if (state !in setOf(PledgeState.CREATED, PledgeState.ABANDONED) && BooksResourceKind.AUTHORIZATION !in kinds) {
        return BooksMatch.UNKNOWN
    }
    if (state in setOf(PledgeState.CAPTURED, PledgeState.CAPTURE_PENDING, PledgeState.REFUNDED,
            PledgeState.REFUND_PENDING, PledgeState.REFUND_FAILED, PledgeState.PARTIALLY_REFUNDED) &&
        BooksResourceKind.CAPTURE !in kinds) return BooksMatch.UNKNOWN
    if (state in setOf(PledgeState.REFUNDED, PledgeState.REFUND_PENDING) && BooksResourceKind.REFUND !in kinds) {
        return BooksMatch.UNKNOWN
    }
    return if (resources.all { it.status in expectedStatuses(state, it.kind) }) BooksMatch.MATCH else BooksMatch.MISMATCH
}

private fun expectedStatuses(state: PledgeState, kind: BooksResourceKind): Set<PaymentStatus> = when (kind) {
    BooksResourceKind.ORDER -> when (state) {
        PledgeState.CREATED, PledgeState.ABANDONED -> setOf(PaymentStatus.CREATED, PaymentStatus.PENDING, PaymentStatus.APPROVED)
        PledgeState.VOIDED, PledgeState.VOID_FAILED -> setOf(PaymentStatus.COMPLETED, PaymentStatus.VOIDED,
            PaymentStatus.CREATED, PaymentStatus.APPROVED)
        else -> setOf(PaymentStatus.COMPLETED)
    }
    BooksResourceKind.AUTHORIZATION -> when (state) {
        PledgeState.AUTHORIZED -> setOf(PaymentStatus.CREATED)
        PledgeState.VOIDED -> setOf(PaymentStatus.VOIDED, PaymentStatus.EXPIRED)
        PledgeState.VOID_FAILED, PledgeState.CAPTURE_FAILED -> setOf(PaymentStatus.CREATED, PaymentStatus.DECLINED)
        PledgeState.CAPTURE_PENDING -> setOf(PaymentStatus.CREATED, PaymentStatus.CAPTURED)
        PledgeState.CAPTURED, PledgeState.REFUNDED, PledgeState.REFUND_PENDING, PledgeState.REFUND_FAILED,
        PledgeState.PARTIALLY_REFUNDED -> setOf(PaymentStatus.CAPTURED)
        else -> emptySet()
    }
    BooksResourceKind.CAPTURE -> when (state) {
        PledgeState.CAPTURED, PledgeState.REFUND_PENDING, PledgeState.REFUND_FAILED -> setOf(PaymentStatus.COMPLETED)
        PledgeState.CAPTURE_PENDING -> setOf(PaymentStatus.PENDING)
        PledgeState.CAPTURE_FAILED, PledgeState.VOIDED, PledgeState.VOID_FAILED -> setOf(PaymentStatus.DECLINED, PaymentStatus.FAILED)
        PledgeState.REFUNDED -> setOf(PaymentStatus.REFUNDED)
        PledgeState.PARTIALLY_REFUNDED -> setOf(PaymentStatus.PARTIALLY_REFUNDED)
        else -> emptySet()
    }
    BooksResourceKind.REFUND -> when (state) {
        PledgeState.REFUNDED, PledgeState.PARTIALLY_REFUNDED -> setOf(PaymentStatus.COMPLETED)
        PledgeState.REFUND_PENDING -> setOf(PaymentStatus.PENDING)
        PledgeState.REFUND_FAILED -> setOf(PaymentStatus.FAILED)
        else -> emptySet()
    }
}

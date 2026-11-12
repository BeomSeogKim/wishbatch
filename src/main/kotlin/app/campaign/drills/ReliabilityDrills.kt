package app.campaign.drills

import app.campaign.books.BooksPledgeRow
import app.campaign.books.BooksPledgeSnapshot
import app.campaign.books.BooksResource
import app.campaign.books.BooksResourceKind
import app.campaign.books.booksMatch
import app.campaign.model.CampaignReason
import app.campaign.model.CampaignState
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.model.WishKind
import app.campaign.port.AnalyzedWish
import app.campaign.port.PaymentStatus
import app.campaign.rules.PaymentAction
import app.campaign.rules.authorizedUnits
import app.campaign.rules.capturedUnits
import app.campaign.rules.funded
import app.campaign.rules.idempotencyKey
import app.campaign.rules.allRequestCount
import app.campaign.rules.requireCampaignTransition
import app.campaign.rules.requirePledgeTransition
import app.campaign.rules.thresholdMet
import java.time.Instant
import java.util.UUID

/** Educational scripts own only disposable in-memory objects, never application payment services. */
object ReliabilityDrills {
    fun play(): List<DrillResult> = listOf(declined(), timeout(), duplicateWebhook(), unavailable())

    private fun declined(): DrillResult {
        val first = pledge("declined-paid", PledgeState.AUTHORIZED)
        val second = pledge("declined-hold", PledgeState.AUTHORIZED)
        val run = Playback("capture-declined", "Capture declined",
            "A simulated shortfall refunds a completed payment and releases the remaining hold.",
            CampaignState.OPEN, listOf(first, second))
        run.confirm(2)
        run.capture(first)
        run.move(second, PledgeState.CAPTURE_FAILED)
        run.provider[second.id] = resources(second, PaymentStatus.CREATED, PaymentStatus.DECLINED)
        run.record("Capture declined", "DECLINED", "No completed capture effect for this pledge.",
            second, PaymentAction.CAPTURE)
        run.settle(2)
        run.move(second, PledgeState.VOIDED)
        run.provider[second.id] = resources(second, PaymentStatus.VOIDED, PaymentStatus.DECLINED)
        run.record("Release remaining hold", "VOIDED", "The simulated provider confirms the hold was voided.",
            second, PaymentAction.VOID, run.effect(second, PaymentAction.VOID))
        run.refund(first, pending = true)
        run.refund(first)
        return run.result()
    }

    private fun timeout(): DrillResult {
        val pledge = pledge("timeout", PledgeState.AUTHORIZED)
        val run = Playback("capture-timeout", "Capture timeout followed by recovery",
            "An ambiguous response counts as zero paid units. A later simulated read recovers the original capture, " +
                "then refunds it because the shortfall outcome is already final.", CampaignState.OPEN, listOf(pledge))
        run.confirm(1)
        val effect = run.effect(pledge, PaymentAction.CAPTURE)
        run.move(pledge, PledgeState.CAPTURE_PENDING)
        run.provider[pledge.id] = resources(pledge, null, null)
        run.record("Capture response lost", "TIMEOUT", "The script assigns one provider capture effect, but its response is unavailable. " +
            "Local payment remains unresolved; missing evidence is UNKNOWN.", pledge, PaymentAction.CAPTURE, effect)
        run.settle(1)
        run.provider[pledge.id] = resources(pledge, PaymentStatus.CAPTURED, PaymentStatus.COMPLETED)
        run.record("Later simulated provider read", "CAPTURED", "Authorization evidence reports a capture; do not create a new payment key.",
            pledge, PaymentAction.READ_AUTHORIZATION)
        val replayEffect = run.effect(pledge, PaymentAction.CAPTURE)
        run.record("Replay original capture key", "COMPLETED", "The original key returns the same simulated capture; there is no second capture effect.",
            pledge, PaymentAction.CAPTURE, replayEffect)
        run.move(pledge, PledgeState.CAPTURED)
        run.record("Read recovered capture", "COMPLETED", "A simulated capture read confirms full amount, currency, and identity before counting payment.",
            pledge, PaymentAction.READ_CAPTURE)
        run.refund(pledge)
        return run.result()
    }

    private fun duplicateWebhook(): DrillResult {
        val pledge = pledge("webhook", PledgeState.CAPTURE_PENDING)
        val run = Playback("duplicate-webhook", "Duplicate webhook",
            "Two copies of one synthetic event trigger one effective processing. The simulated provider read, " +
                "rather than the event body, supplies payment evidence.", CampaignState.NOT_FUNDED, listOf(pledge))
        val eventId = "sim-event-duplicate"
        repeat(2) {
            val accepted = run.events.add(eventId)
            run.record("Synthetic webhook delivery", if (accepted) "ACCEPTED" else "DUPLICATE",
                "Synthetic event $eventId claims DECLINED. " + if (accepted) "Re-read simulated provider evidence before acting."
                else "Already processed; no additional provider read or compensation effect.",
                providerId = eventId, effective = accepted)
            if (accepted) {
                run.provider[pledge.id] = resources(pledge, PaymentStatus.CAPTURED, PaymentStatus.COMPLETED)
                run.move(pledge, PledgeState.CAPTURED)
                run.record("Re-read simulated provider", "COMPLETED", "The simulated capture is completed despite the event body's claim; " +
                    "the terminal shortfall requires a full refund.", pledge, PaymentAction.READ_CAPTURE)
                run.refund(pledge)
            }
        }
        return run.result()
    }

    private fun unavailable(): DrillResult {
        val pledge = pledge("unavailable", PledgeState.CREATED)
        val run = Playback("pledge-unavailable", "Provider unavailable during pledge",
            "An unavailable simulated order response is not evidence of authorization or payment, and is never a books match.",
            CampaignState.OPEN, listOf(pledge))
        run.provider[pledge.id] = listOf(resource(pledge, BooksResourceKind.ORDER, null))
        run.record("Create order unavailable", "UNAVAILABLE", "No authorization or completed payment is confirmed. " +
            "The sample order ID is only a simulated lookup placeholder, not a provider receipt.", pledge, PaymentAction.CREATE_ORDER)
        return run.result()
    }

    private class Playback(
        val id: String,
        val title: String,
        val summary: String,
        var campaign: CampaignState,
        val pledges: List<Pledge>,
    ) {
        var reason: CampaignReason? = if (campaign == CampaignState.NOT_FUNDED) CampaignReason.CAPTURE_SHORTFALL else null
        val provider = pledges.associate { pledge ->
            pledge.id to when (pledge.state) {
                PledgeState.AUTHORIZED -> resources(pledge, PaymentStatus.CREATED)
                PledgeState.CAPTURE_PENDING -> resources(pledge, PaymentStatus.CAPTURED, PaymentStatus.PENDING)
                else -> listOf(resource(pledge, BooksResourceKind.ORDER, null))
            }
        }.toMutableMap()
        val steps = mutableListOf<DrillStep>()
        val effects = linkedMapOf<String, Int>()
        val events = linkedSetOf<String>()
        val before = snapshot()
        val beforeBooks = pledges.associate { it.id to compareSimulatedBooks(it, provider.getValue(it.id)) }

        fun snapshot(): DrillSnapshot {
            val wishes = (1..4).map { AnalyzedWish(sampleId("$id-wish-$it"), WishKind.REQUEST, emptyList()) }
            return DrillSnapshot(campaign, reason, DrillCounts(allRequestCount(wishes), authorizedUnits(pledges), capturedUnits(pledges)),
                pledges.filter { it.state in setOf(PledgeState.CAPTURE_PENDING, PledgeState.REFUND_PENDING,
                    PledgeState.REFUND_FAILED, PledgeState.VOID_FAILED, PledgeState.CAPTURE_FAILED) ||
                    it.state == PledgeState.CREATED && provider.getValue(it.id).any { resource -> resource.status == null } ||
                    campaign == CampaignState.NOT_FUNDED && it.state in setOf(PledgeState.CAPTURED, PledgeState.AUTHORIZED) }
                    .map { "${it.paypalOrderId}: ${it.state}" }, pledges.associate { it.id to it.state })
        }

        fun move(pledge: Pledge, state: PledgeState) {
            pledge.state = requirePledgeTransition(pledge.state, state)
        }

        fun confirm(minimum: Int) {
            require(thresholdMet(authorizedUnits(pledges), minimum))
            campaign = requireCampaignTransition(campaign, CampaignState.SETTLING)
            record("Maker confirmation", "SETTLING", "The simulated authorized minimum of $minimum units permits capture attempts.")
        }

        fun settle(minimum: Int) {
            val enough = funded(capturedUnits(pledges), minimum)
            reason = if (enough) null else CampaignReason.CAPTURE_SHORTFALL
            campaign = requireCampaignTransition(campaign, if (enough) CampaignState.FUNDED else CampaignState.NOT_FUNDED, reason)
            record("Evaluate completed payments", campaign.name, "Only completed captures count toward the simulated minimum of $minimum units.")
        }

        fun effect(pledge: Pledge, action: PaymentAction): Boolean {
            val key = idempotencyKey(pledge.id, action)
            if (key in effects) return false
            effects[key] = 1
            return true
        }

        fun capture(pledge: Pledge) {
            move(pledge, PledgeState.CAPTURED)
            provider[pledge.id] = resources(pledge, PaymentStatus.CAPTURED, PaymentStatus.COMPLETED)
            record("Capture completed", "COMPLETED", "Full simulated amount confirmed.",
                pledge, PaymentAction.CAPTURE, effect(pledge, PaymentAction.CAPTURE))
        }

        fun refund(pledge: Pledge, pending: Boolean = false) {
            require(campaign == CampaignState.NOT_FUNDED)
            require(pledge.amount > 0 && provider.getValue(pledge.id).any {
                it.kind == BooksResourceKind.CAPTURE && it.status == PaymentStatus.COMPLETED &&
                    it.amount == pledge.amount && it.currency == pledge.currency && it.evidenceMatches
            })
            val effective = effect(pledge, PaymentAction.REFUND)
            move(pledge, if (pending) PledgeState.REFUND_PENDING else PledgeState.REFUNDED)
            provider[pledge.id] = resources(pledge, PaymentStatus.CAPTURED,
                if (pending) PaymentStatus.COMPLETED else PaymentStatus.REFUNDED,
                if (pending) PaymentStatus.PENDING else PaymentStatus.COMPLETED)
            record(if (pending) "Refund pending" else "Refund completed", if (pending) "PENDING" else "COMPLETED",
                if (pending) "Shortfall compensation remains under Needs attention."
                else "The full simulated refund completes with the original refund key; no unknown remainder is refunded.",
                pledge, PaymentAction.REFUND, effective)
        }

        fun record(
            operation: String, status: String, detail: String, pledge: Pledge? = null,
            action: PaymentAction? = null, effective: Boolean = false, providerId: String? = null,
        ) {
            val resourceId = providerId ?: when (action) {
                PaymentAction.CAPTURE, PaymentAction.READ_AUTHORIZATION, PaymentAction.VOID -> pledge?.authorizationId
                PaymentAction.READ_CAPTURE, PaymentAction.REFUND -> pledge?.captureId
                else -> pledge?.paypalOrderId
            }
            val endpoint = when (action) {
                PaymentAction.CREATE_ORDER -> "POST /v2/checkout/orders"
                PaymentAction.CAPTURE -> "POST /v2/payments/authorizations/$resourceId/capture"
                PaymentAction.VOID -> "POST /v2/payments/authorizations/$resourceId/void"
                PaymentAction.REFUND -> "POST /v2/payments/captures/$resourceId/refund"
                PaymentAction.READ_AUTHORIZATION -> "GET /v2/payments/authorizations/$resourceId"
                PaymentAction.READ_CAPTURE -> "GET /v2/payments/captures/$resourceId"
                else -> null
            }
            steps += DrillStep("Simulated T+${steps.size + 1}s", operation, endpoint, resourceId,
                if (pledge != null && action != null) idempotencyKey(pledge.id, action) else null,
                status, detail, effective, snapshot())
        }

        fun result(): DrillResult = DrillResult(id, title, summary, before, snapshot(), pledges.map {
            BooksPledgeRow(it.id, it.qty, it.amount, it.currency, beforeBooks.getValue(it.id), compareSimulatedBooks(it, provider.getValue(it.id)))
        }, steps.toList(), effects.toMap(), events.size)
    }

    private fun pledge(name: String, state: PledgeState): Pledge = Pledge(sampleId("campaign-${name.substringBefore('-')}"), 1, 1200L, "USD",
        state = state, paypalOrderId = "sim-order-$name", authorizationId = "sim-authorization-$name",
        captureId = "sim-capture-$name", refundId = "sim-refund-$name", createdAt = Instant.EPOCH, id = sampleId(name))

    private fun resources(pledge: Pledge, authorization: PaymentStatus?, capture: PaymentStatus? = null,
        refund: PaymentStatus? = null): List<BooksResource> = buildList {
        add(resource(pledge, BooksResourceKind.ORDER, PaymentStatus.COMPLETED))
        add(resource(pledge, BooksResourceKind.AUTHORIZATION, authorization))
        if (capture != null || pledge.state == PledgeState.CAPTURE_PENDING) add(resource(pledge, BooksResourceKind.CAPTURE, capture))
        if (refund != null) add(resource(pledge, BooksResourceKind.REFUND, refund))
    }

    private fun resource(pledge: Pledge, kind: BooksResourceKind, status: PaymentStatus?): BooksResource =
        BooksResource(kind, expectedId(pledge, kind), status, status?.name,
            if (status == null) null else pledge.amount, if (status == null) null else pledge.currency, status != null)

    private fun sampleId(name: String): UUID = UUID.nameUUIDFromBytes("sim-$name".toByteArray(Charsets.UTF_8))
}

internal fun compareSimulatedBooks(pledge: Pledge, resources: List<BooksResource>): BooksPledgeSnapshot {
    val evidence = resources.map { it.copy(evidenceMatches = it.evidenceMatches && it.id == expectedId(pledge, it.kind) &&
        it.amount == pledge.amount && it.currency == pledge.currency) }
    return BooksPledgeSnapshot(pledge.state, evidence, booksMatch(pledge.state, evidence))
}

private fun expectedId(pledge: Pledge, kind: BooksResourceKind): String = requireNotNull(when (kind) {
    BooksResourceKind.ORDER -> pledge.paypalOrderId
    BooksResourceKind.AUTHORIZATION -> pledge.authorizationId
    BooksResourceKind.CAPTURE -> pledge.captureId
    BooksResourceKind.REFUND -> pledge.refundId
})

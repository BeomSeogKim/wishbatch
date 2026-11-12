package app.campaign.books

import app.campaign.model.Event
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.port.AuthorizationId
import app.campaign.port.CaptureId
import app.campaign.port.OrderId
import app.campaign.port.PaymentGateway
import app.campaign.port.PaymentResult
import app.campaign.port.RefundId
import app.campaign.service.CampaignPersistence
import app.campaign.service.CampaignRef
import app.campaign.service.CampaignService
import app.campaign.service.Rejection
import app.campaign.service.ServiceResult
import app.campaign.store.CampaignRepository
import app.campaign.store.EventRepository
import app.campaign.store.OfferRepository
import app.campaign.store.PledgeRepository
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.UUID

@Service
class BooksCheckService(
    private val campaigns: CampaignRepository,
    private val offers: OfferRepository,
    private val pledges: PledgeRepository,
    private val events: EventRepository,
    private val gateway: PaymentGateway,
    private val service: CampaignService,
    private val persistence: CampaignPersistence,
    private val mapper: JsonMapper,
    private val clock: Clock,
) {
    fun check(ref: CampaignRef): ServiceResult<BooksCheck> {
        val before = load(ref) ?: return ServiceResult.Rejected(Rejection.NOT_FOUND)
        val id = UUID.randomUUID()
        val startedAt = clock.instant()
        val beforeSnapshots = before.associate { it.id to observe(it, id) }
        // Only the campaign boundary may apply payment transitions or compensation.
        val reconciled = service.reconcile(ref)
        if (reconciled is ServiceResult.Rejected) return reconciled
        val after = load(ref) ?: return ServiceResult.Rejected(Rejection.NOT_FOUND)
        val rows = after.map { pledge ->
            val current = observe(pledge, id)
            BooksPledgeRow(pledge.id, pledge.qty, pledge.amount, pledge.currency,
                beforeSnapshots[pledge.id] ?: BooksPledgeSnapshot(pledge.state, emptyList(), BooksMatch.UNKNOWN), current)
        }
        val result = BooksCheck(id, ref.campaignId, startedAt, clock.instant(), rows)
        persistence.checkpoint {
            events.saveAndFlush(Event(ref.campaignId, "BOOKS_CHECK", detail = mapper.writeValueAsString(result),
                createdAt = result.checkedAt, id = result.id))
        }
        return ServiceResult.Success(result)
    }

    /** Reads stored observations without provider calls, reconciliation, or buyer information. */
    fun history(ref: CampaignRef): ServiceResult<List<BooksCheck>> = persistence.checkpoint {
        if (!owns(ref)) return@checkpoint ServiceResult.Rejected(Rejection.NOT_FOUND)
        ServiceResult.Success(events.findByCampaignIdOrderByCreatedAtAscIdAsc(ref.campaignId)
            .filter { it.type == "BOOKS_CHECK" }
            .map { mapper.readValue(requireNotNull(it.detail), BooksCheck::class.java) })
    }

    private fun owns(ref: CampaignRef): Boolean {
        val campaign = campaigns.findById(ref.campaignId).orElse(null) ?: return false
        return offers.findByIdAndWorkspaceId(campaign.offerId, ref.workspaceId) != null
    }

    private fun load(ref: CampaignRef): List<Pledge>? = persistence.checkpoint {
        if (!owns(ref)) null else pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(ref.campaignId)
    }

    private fun observe(pledge: Pledge, checkId: UUID): BooksPledgeSnapshot {
        val resources = mutableListOf<BooksResource>()
        val authorizationIds = linkedSetOf<String>().apply { pledge.authorizationId?.let(::add) }
        val captureIds = linkedSetOf<String>().apply { pledge.captureId?.let(::add) }
        val refundIds = linkedSetOf<String>().apply { pledge.refundId?.let(::add) }
        pledge.paypalOrderId?.let { id ->
            val result = gateway.readOrder(OrderId(id), "books-$checkId")
            val order = (result as? PaymentResult.Received)?.resource
            order?.authorizationIds?.forEach { authorizationIds.add(it.value) }
            order?.captureIds?.forEach { captureIds.add(it.value) }
            order?.refundIds?.forEach { refundIds.add(it.value) }
            resources += resource(pledge, BooksResourceKind.ORDER, id, result, order?.providerStatus,
                order?.amount?.minorUnits, order?.amount?.currency,
                order == null || order.orderId.value == id &&
                    order.authorizationIds.all { it.value == pledge.authorizationId } &&
                    order.captureIds.all { it.value == pledge.captureId } && order.refundIds.all { it.value == pledge.refundId })
        }
        authorizationIds.forEach { id ->
            val result = gateway.readAuthorization(AuthorizationId(id), "books-$checkId")
            val authorization = (result as? PaymentResult.Received)?.resource
            authorization?.captureId?.let { captureIds.add(it.value) }
            resources += resource(pledge, BooksResourceKind.AUTHORIZATION, id, result, null,
                authorization?.amount?.minorUnits, authorization?.amount?.currency,
                authorization == null || authorization.authorizationId.value == id && id == pledge.authorizationId &&
                    (authorization.orderId == null || authorization.orderId.value == pledge.paypalOrderId) &&
                    (authorization.captureId == null || authorization.captureId.value == pledge.captureId))
        }
        captureIds.forEach { id ->
            val result = gateway.readCapture(CaptureId(id), "books-$checkId")
            val capture = (result as? PaymentResult.Received)?.resource
            resources += resource(pledge, BooksResourceKind.CAPTURE, id, result, null,
                capture?.amount?.minorUnits, capture?.amount?.currency,
                capture == null || capture.captureId.value == id && id == pledge.captureId &&
                    (capture.authorizationId == null || capture.authorizationId.value == pledge.authorizationId))
        }
        refundIds.forEach { id ->
            val result = gateway.readRefund(RefundId(id), "books-$checkId")
            val refund = (result as? PaymentResult.Received)?.resource
            resources += resource(pledge, BooksResourceKind.REFUND, id, result, null,
                refund?.amount?.minorUnits, refund?.amount?.currency,
                refund == null || refund.refundId.value == id && id == pledge.refundId &&
                    (refund.captureId == null || refund.captureId.value == pledge.captureId))
        }
        return BooksPledgeSnapshot(pledge.state, resources, booksMatch(pledge.state, resources))
    }

    private fun resource(
        pledge: Pledge, kind: BooksResourceKind, id: String, result: PaymentResult<*>, providerStatus: String?,
        amount: Long?, currency: String?, identityMatches: Boolean,
    ): BooksResource {
        val status = if (result is PaymentResult.Received) result.status else null
        val amountMatches = currency == pledge.currency && if (kind == BooksResourceKind.REFUND &&
            pledge.state == PledgeState.PARTIALLY_REFUNDED) {
            amount != null && amount > 0 && amount < pledge.amount
        } else amount == pledge.amount
        val resource = BooksResource(kind, id, status, providerStatus, amount, currency, identityMatches && amountMatches)
        persistence.checkpoint {
            val endpoint = when (kind) {
                BooksResourceKind.ORDER -> "GET /v2/checkout/orders/$id"
                BooksResourceKind.AUTHORIZATION -> "GET /v2/payments/authorizations/$id"
                BooksResourceKind.CAPTURE -> "GET /v2/payments/captures/$id"
                BooksResourceKind.REFUND -> "GET /v2/payments/refunds/$id"
            }
            events.save(Event(pledge.campaignId, "BOOKS_RESOURCE_READ", pledge.id, endpoint, id,
                status?.name ?: "UNKNOWN", result.debugId, createdAt = clock.instant()))
        }
        return resource
    }
}

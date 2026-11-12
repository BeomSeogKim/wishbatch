package app.campaign.terms

import app.campaign.model.Event
import app.campaign.model.Pledge
import app.campaign.port.PaymentResult
import app.campaign.port.PaymentStatus
import app.campaign.provenance.DecisionRecordService
import app.campaign.service.CampaignPayments
import app.campaign.service.CampaignPersistence
import app.campaign.store.CampaignRepository
import app.campaign.store.EventRepository
import app.campaign.store.OfferRepository
import app.campaign.store.PledgeRepository
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

@Service
class TermsCheckService(
    private val persistence: CampaignPersistence,
    private val pledges: PledgeRepository,
    private val campaigns: CampaignRepository,
    private val offers: OfferRepository,
    private val records: DecisionRecordService,
    private val payments: CampaignPayments,
    private val events: EventRepository,
    private val mapper: JsonMapper,
    private val clock: Clock,
) {
    /** Payment reconciliation may record a check; seal and decision-record queries never call this. */
    fun check(pledge: Pledge, stage: TermsCheckStage): TermsCheckObservation {
        val saved = persistence.checkpoint { pledges.findById(pledge.id).orElseThrow() }
        val campaign = persistence.checkpoint { campaigns.findById(saved.campaignId).orElseThrow() }
        val offer = persistence.checkpoint { offers.findById(campaign.offerId).orElseThrow() }
        var reason: String? = null
        val seal = try {
            persistence.checkpoint { records.sealedOffer(offer.workspaceId, offer.id) }
        } catch (_: SealedOfferChanged) {
            reason = "SEAL_CHANGED"
            null
        }
        val expected = seal?.let {
            try { Math.multiplyExact(it.unitPriceMinor, saved.qty.toLong()) } catch (_: ArithmeticException) {
                reason = "AMOUNT_OVERFLOW"
                null
            }
        }
        if (reason == null) reason = when {
            seal == null -> "SEAL_UNAVAILABLE"
            campaign.openedAt == null -> "PUBLICATION_TIME_UNAVAILABLE"
            saved.qty <= 0 || expected != saved.amount || seal.currency != saved.currency -> "PLEDGE_MISMATCH"
            saved.paypalOrderId.isNullOrBlank() -> "ORDER_ID_UNAVAILABLE"
            else -> null
        }
        // Even a local terms failure must not be interpreted as evidence about a provider hold.
        val result = if (reason != null) null else try {
            payments.readOrder(saved)
        } catch (_: RuntimeException) {
            PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE)
        }
        val order = (result as? PaymentResult.Received)?.resource
        if (reason == null) reason = when {
            order == null -> "ORDER_UNAVAILABLE"
            order.orderId.value != saved.paypalOrderId -> "ORDER_ID_MISMATCH"
            order.amount == null -> "ORDER_AMOUNT_UNAVAILABLE"
            order.amount.minorUnits != expected || order.amount.currency != seal?.currency -> "ORDER_AMOUNT_MISMATCH"
            result.status !in setOf(PaymentStatus.APPROVED, PaymentStatus.COMPLETED) -> "ORDER_STATUS_MISMATCH"
            else -> null
        }
        val outcome = when (reason) {
            null -> TermsCheckOutcome.MATCH
            "SEAL_UNAVAILABLE", "PUBLICATION_TIME_UNAVAILABLE", "ORDER_ID_UNAVAILABLE", "ORDER_UNAVAILABLE",
            "ORDER_AMOUNT_UNAVAILABLE" -> TermsCheckOutcome.UNAVAILABLE
            else -> TermsCheckOutcome.MISMATCH
        }
        val observation = TermsCheckObservation(stage = stage, offerId = offer.id, termsHash = seal?.termsHash,
            quantity = saved.qty, expectedAmountMinor = expected, expectedCurrency = seal?.currency,
            orderId = saved.paypalOrderId, providerOrderId = order?.orderId?.value,
            providerAmountMinor = order?.amount?.minorUnits, providerCurrency = order?.amount?.currency,
            providerStatus = result?.status?.name, outcome = outcome, reason = reason ?: "TERMS_MATCH",
            checkedAt = clock.instant())
        persistence.checkpoint {
            events.saveAndFlush(Event(saved.campaignId, "TERMS_CHECK", saved.id,
                detail = mapper.writeValueAsString(observation), createdAt = observation.checkedAt))
        }
        return observation
    }
}

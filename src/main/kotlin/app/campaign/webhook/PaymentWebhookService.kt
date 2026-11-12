package app.campaign.webhook

import app.campaign.model.Pledge
import app.campaign.port.PaymentGateway
import app.campaign.port.WebhookVerification
import app.campaign.service.CampaignRef
import app.campaign.service.CampaignService
import app.campaign.service.ServiceResult
import app.campaign.store.CampaignRepository
import app.campaign.store.OfferRepository
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

enum class WebhookResult { ACCEPTED, INVALID_SIGNATURE, UNAVAILABLE, DISABLED }

@Service
class PaymentWebhookService(
    private val gateway: PaymentGateway,
    private val inbox: WebhookInbox,
    private val entityManager: EntityManager,
    private val campaigns: CampaignRepository,
    private val offers: OfferRepository,
    private val service: CampaignService,
    @Value("\${PAYPAL_WEBHOOK_ID:}") private val webhookId: String,
) {
    private val logger = LoggerFactory.getLogger(PaymentWebhookService::class.java)

    fun receive(event: PaymentWebhook, headers: Map<String, String>, originalJson: String): WebhookResult {
        if (webhookId.isBlank()) return outcome(event, WebhookResult.DISABLED)
        when (gateway.verifyWebhook(webhookId, headers, originalJson)) {
            WebhookVerification.INVALID -> return outcome(event, WebhookResult.INVALID_SIGNATURE)
            WebhookVerification.UNAVAILABLE -> return outcome(event, WebhookResult.UNAVAILABLE)
            WebhookVerification.VERIFIED -> Unit
        }
        val result = if (inbox.receive(event, ::process)) WebhookResult.ACCEPTED else WebhookResult.UNAVAILABLE
        return outcome(event, result)
    }

    private fun outcome(event: PaymentWebhook, result: WebhookResult): WebhookResult {
        val type = knownEvents.firstOrNull { it == event.type } ?: "UNKNOWN"
        logger.info("Payment webhook type={} outcome={}", type, result)
        return result
    }

    private fun process(event: PaymentWebhook): Boolean {
        val field = when {
            event.type == "PAYMENT.AUTHORIZATION.VOIDED" && event.resourceType == "authorization" -> "authorizationId"
            event.type in captureEvents && event.resourceType == "capture" -> "captureId"
            event.type == "PAYMENT.CAPTURE.REFUNDED" && event.resourceType == "refund" -> "refundId"
            else -> return true
        }
        val matches = entityManager.createQuery("select p from Pledge p where p.$field = :id" +
            if (field == "refundId" && event.captureId != null) " or p.captureId = :captureId" else "", Pledge::class.java)
            .setParameter("id", event.resourceId).apply {
                if (field == "refundId" && event.captureId != null) setParameter("captureId", event.captureId)
            }.setMaxResults(2).resultList
        val pledge = matches.singleOrNull() ?: return true
        val campaign = campaigns.findById(pledge.campaignId).orElse(null) ?: return true
        val offer = offers.findById(campaign.offerId).orElse(null) ?: return true
        return service.reconcile(CampaignRef(offer.workspaceId, campaign.id)) is ServiceResult.Success
    }

    private val captureEvents = setOf("PAYMENT.CAPTURE.COMPLETED", "PAYMENT.CAPTURE.DECLINED",
        "PAYMENT.CAPTURE.DENIED", "PAYMENT.CAPTURE.REFUNDED")
    private val knownEvents = captureEvents + "PAYMENT.AUTHORIZATION.VOIDED"
}

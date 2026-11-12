package app.campaign.webhook

import app.campaign.port.PaymentGateway
import app.campaign.port.WebhookVerification
import app.campaign.service.CampaignService
import app.campaign.store.CampaignRepository
import app.campaign.store.OfferRepository
import app.testing.InMemoryPaymentGateway
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaymentWebhookDiagnosticsTests {
    @ParameterizedTest
    @EnumSource(WebhookResult::class)
    fun `each receive outcome logs only an allowlisted type and truthful result`(result: WebhookResult) {
        for (type in listOf("PAYMENT.CAPTURE.COMPLETED", "PAYMENT.AUTHORIZATION.VOIDED", privateType)) {
            capture { logs ->
                val gateway = Gateway(when (result) {
                    WebhookResult.INVALID_SIGNATURE -> WebhookVerification.INVALID
                    WebhookResult.UNAVAILABLE -> WebhookVerification.UNAVAILABLE
                    else -> WebhookVerification.VERIFIED
                })
                var received = 0
                val event = PaymentWebhook("private-event-id", type, "private-resource-type", "private-resource-id", "private-capture-id")
                val inbox = mock(WebhookInbox::class.java) { invocation ->
                    if (invocation.method.name == "receive") {
                        assertEquals(1, gateway.verifications)
                        assertEquals(event, invocation.arguments[0])
                        assertTrue(logs.list.isEmpty())
                        received++
                        true
                    } else Answers.RETURNS_DEFAULTS.answer(invocation)
                }
                val service = service(gateway, inbox, if (result == WebhookResult.DISABLED) "" else "private-webhook-id")
                assertEquals(result, service.receive(event, headers, body))
                assertEquals(if (result == WebhookResult.DISABLED) 0 else 1, gateway.verifications)
                assertEquals(if (result == WebhookResult.ACCEPTED) 1 else 0, received)
                assertLog(logs, if (type == privateType) "UNKNOWN" else type, result)
            }
        }
    }

    @Test
    fun `verified signature with incomplete inbox processing reports unavailable`() {
        capture { logs ->
            val gateway = Gateway(WebhookVerification.VERIFIED)
            var received = false
            val inbox = mock(WebhookInbox::class.java) { invocation ->
                if (invocation.method.name == "receive") {
                    assertEquals(1, gateway.verifications)
                    assertTrue(logs.list.isEmpty())
                    received = true
                    false
                } else Answers.RETURNS_DEFAULTS.answer(invocation)
            }
            assertEquals(WebhookResult.UNAVAILABLE, service(gateway, inbox, "private-webhook-id").receive(
                PaymentWebhook("private-event-id", privateType, "capture", "private-resource-id"), headers, body))
            assertTrue(received)
            assertLog(logs, "UNKNOWN", WebhookResult.UNAVAILABLE)
        }
    }

    private fun service(gateway: PaymentGateway, inbox: WebhookInbox, id: String) = PaymentWebhookService(
        gateway, inbox, mock(EntityManager::class.java), mock(CampaignRepository::class.java),
        mock(OfferRepository::class.java), mock(CampaignService::class.java), id,
    )

    private fun assertLog(logs: ListAppender<ILoggingEvent>, type: String, result: WebhookResult) {
        val event = logs.list.single()
        assertEquals(Level.INFO, event.level)
        assertEquals("Payment webhook type=$type outcome=$result", event.formattedMessage)
        assertNull(event.throwableProxy)
        listOf("private-", "buyer@example.test", "https://example.test", "Bearer", "\n")
            .forEach { assertFalse(event.formattedMessage.contains(it)) }
    }

    private fun capture(action: (ListAppender<ILoggingEvent>) -> Unit) {
        val logger = LoggerFactory.getLogger(PaymentWebhookService::class.java) as Logger
        val previousLevel = logger.level
        logger.level = Level.INFO
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            action(logs)
        } finally {
            logger.detachAppender(logs)
            logger.level = previousLevel
            logs.stop()
        }
    }

    private class Gateway(private val verification: WebhookVerification) : PaymentGateway by InMemoryPaymentGateway() {
        var verifications = 0
        override fun verifyWebhook(webhookId: String, headers: Map<String, String>, eventJson: String): WebhookVerification {
            assertEquals("private-webhook-id", webhookId)
            assertEquals(PaymentWebhookDiagnosticsTests.headers, headers)
            assertEquals(body, eventJson)
            verifications++
            return verification
        }
    }

    companion object {
        private const val privateType = "private-type\nPAYMENT.CAPTURE.COMPLETED"
        private val headers = mapOf("private-header" to "Bearer private-token")
        private const val body = """{"buyer":"buyer@example.test","url":"https://example.test/private-body"}"""
    }
}

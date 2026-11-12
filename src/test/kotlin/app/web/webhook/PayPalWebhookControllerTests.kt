package app.web.webhook

import app.campaign.model.Campaign
import app.campaign.model.CampaignState
import app.campaign.model.Money
import app.campaign.model.Offer
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.model.Workspace
import app.campaign.port.CaptureDetails
import app.campaign.port.CaptureId
import app.campaign.port.PaymentGateway
import app.campaign.port.PaymentResult
import app.campaign.port.PaymentStatus
import app.campaign.port.WebhookVerification
import app.campaign.service.CampaignService
import app.campaign.store.CampaignRepository
import app.campaign.store.EventRepository
import app.campaign.store.OfferRepository
import app.campaign.store.PledgeRepository
import app.campaign.store.WorkspaceRepository
import app.campaign.webhook.PaymentWebhook
import app.campaign.webhook.PaymentWebhookService
import app.campaign.webhook.WebhookInbox
import app.campaign.webhook.WebhookResult
import app.testing.InMemoryPaymentGateway
import app.testing.PaymentCall
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(properties = ["PAYPAL_WEBHOOK_ID=SYNTHETICHOOK"])
@AutoConfigureMockMvc
@Import(PayPalWebhookControllerTests.Configuration::class)
class PayPalWebhookControllerTests @Autowired constructor(
    private val mvc: MockMvc,
    private val gateway: WebhookGateway,
    private val workspaces: WorkspaceRepository,
    private val offers: OfferRepository,
    private val campaigns: CampaignRepository,
    private val pledges: PledgeRepository,
    private val events: EventRepository,
    private val jdbc: JdbcTemplate,
    private val inbox: WebhookInbox,
    private val entityManager: EntityManager,
    private val campaignService: CampaignService,
) {
    private val fixtureIds = mutableListOf<UUID>()
    private val eventIds = mutableListOf<String>()
    private var firstCall = 0

    @BeforeEach
    fun reset() {
        gateway.verification = WebhookVerification.VERIFIED
        gateway.verifications.clear()
        gateway.payments.readCaptureResults.clear()
        firstCall = gateway.payments.calls.size
    }

    @AfterEach
    fun clean() {
        eventIds.forEach { jdbc.update("delete from paypal_webhook_events where event_id = ?", it) }
        fixtureIds.forEach { workspaceId ->
            val workspaceOffers = offers.findByWorkspaceId(workspaceId)
            campaigns.findByOfferIdIn(workspaceOffers.map { it.id }).forEach {
                events.deleteAll(events.findByCampaignIdOrderByCreatedAtAscIdAsc(it.id))
                pledges.deleteAll(pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(it.id))
                campaigns.delete(it)
            }
            offers.deleteAll(workspaceOffers)
            workspaces.deleteById(workspaceId)
        }
    }

    @Test
    fun `bad signature cannot store an event or call reconciliation`() {
        val pledge = fixture()
        val id = eventId()
        gateway.verification = WebhookVerification.INVALID
        post(body(id, pledge.captureId!!)).andExpect { status { isUnauthorized() } }
        assertEquals(0, eventCount(id))
        assertTrue(calls().isEmpty())
        assertEquals(PledgeState.CAPTURE_PENDING, pledges.findById(pledge.id).orElseThrow().state)
    }

    @Test
    fun `duplicate deliveries verify every signature but reconcile only once`() {
        val pledge = fixture()
        val id = eventId()
        completedCapture(pledge)
        val original = "  " + body(id, pledge.captureId!!) + "\n"
        repeat(2) { post(original).andExpect { status { isOk() } } }
        assertEquals(1, eventCount(id))
        assertEquals(2, gateway.verifications.size)
        assertEquals(original, gateway.verifications.first().second)
        assertEquals("synthetic-signature", gateway.verifications.first().first["paypal-transmission-sig"])
        assertEquals(1, calls().filterIsInstance<PaymentCall.ReadCapture>().size)
        assertEquals(PledgeState.CAPTURED, pledges.findById(pledge.id).orElseThrow().state)
        gateway.verification = WebhookVerification.INVALID
        post(original).andExpect { status { isUnauthorized() } }
        assertEquals(1, calls().size)
    }

    @Test
    fun `out of order completed then denied events use the latest provider resource without regressing state`() {
        val pledge = fixture()
        gateway.payments.readCaptureResults.add(PaymentResult.Received(
            CaptureDetails(CaptureId(pledge.captureId!!), Money(200, "USD")), PaymentStatus.REFUNDED))
        post(body(eventId(), pledge.captureId!!)).andExpect { status { isOk() } }
        assertEquals(PledgeState.REFUNDED, pledges.findById(pledge.id).orElseThrow().state)
        post(body(eventId(), pledge.captureId!!, "PAYMENT.CAPTURE.DECLINED", "DECLINED"))
            .andExpect { status { isOk() } }
        assertEquals(PledgeState.REFUNDED, pledges.findById(pledge.id).orElseThrow().state)
        assertEquals(1, calls().filterIsInstance<PaymentCall.ReadCapture>().size)
        assertEquals(CampaignState.FUNDED, campaigns.findById(pledge.campaignId).orElseThrow().state)
    }

    @Test
    fun `unknown resources and unsupported event types are stored once without provider reads`() {
        val unknown = eventId()
        repeat(2) { post(body(unknown, "UNKNOWN")).andExpect { status { isOk() } } }
        val ignored = eventId()
        post(body(ignored, "UNKNOWN", "CHECKOUT.ORDER.APPROVED")).andExpect { status { isOk() } }
        assertEquals(1, eventCount(unknown))
        assertEquals(1, eventCount(ignored))
        assertTrue(calls().isEmpty())
    }

    @Test
    fun `refund resource up link resolves the stored capture and only triggers provider reconciliation`() {
        for (host in listOf("api-m.sandbox.paypal.com", "api.sandbox.paypal.com")) {
            val pledge = fixture()
            completedCapture(pledge)
            val id = eventId()
            val json = """{"id":"$id","event_type":"PAYMENT.CAPTURE.REFUNDED","resource_type":"refund",
                "resource":{"id":"REFUND-EXTERNAL","status":"COMPLETED","links":[{"rel":"up",
                "href":"https://$host/v2/payments/captures/${pledge.captureId}"}]}}"""
            post(json).andExpect { status { isOk() } }
            assertEquals(PledgeState.CAPTURED, pledges.findById(pledge.id).orElseThrow().state)
        }
        assertEquals(2, calls().filterIsInstance<PaymentCall.ReadCapture>().size)
    }

    @Test
    fun `missing webhook ID and unavailable verification reject before persistence`() {
        val disabled = PaymentWebhookService(gateway, inbox, entityManager, campaigns, offers, campaignService, "")
        val id = eventId()
        assertEquals(WebhookResult.DISABLED, disabled.receive(
            PaymentWebhook(id, "PAYMENT.CAPTURE.COMPLETED", "capture", "UNKNOWN"), emptyMap(), "{}"))
        assertTrue(gateway.verifications.isEmpty())
        gateway.verification = WebhookVerification.UNAVAILABLE
        post(body(id, "UNKNOWN")).andExpect { status { isServiceUnavailable() } }
        assertEquals(0, eventCount(id))
    }

    @Test
    fun `malformed and oversized identifiers are rejected without verification`() {
        for (json in listOf("not-json", "{}", body("x".repeat(256), "UNKNOWN"))) {
            post(json).andExpect { status { isBadRequest() } }
        }
        assertTrue(gateway.verifications.isEmpty())
    }

    @Test
    fun `invalid optional resource references return bad request without persistence`() {
        val id = eventId()
        val malformedLink = """{"id":"$id","event_type":"PAYMENT.CAPTURE.REFUNDED","resource_type":"refund",
            "resource":{"id":"REFUND1","links":[{"rel":"up","href":"https://invalid host/capture"}]}}"""
        val oversizedReference = """{"id":"$id","event_type":"PAYMENT.CAPTURE.REFUNDED","resource_type":"refund",
            "resource":{"id":"REFUND1","supplementary_data":{"related_ids":{"capture_id":"${"x".repeat(256)}"}}}}"""
        for (json in listOf(malformedLink, oversizedReference)) {
            post(json).andExpect { status { isBadRequest() } }
        }
        assertTrue(gateway.verifications.isEmpty())
        assertEquals(0, eventCount(id))
    }

    private fun fixture(): Pledge {
        val workspace = workspaces.saveAndFlush(Workspace("webhook-${UUID.randomUUID()}", now))
        fixtureIds.add(workspace.id)
        val offer = offers.saveAndFlush(Offer(workspace.id, UUID.randomUUID(), UUID.randomUUID(), "Print", "A5 print",
            emptyMap(), 200, "USD", "Shipping included", "US", 1, 3))
        val campaign = campaigns.saveAndFlush(Campaign(offer.id, CampaignState.FUNDED,
            openedAt = now, closesAt = now.plusSeconds(900), confirmDeadline = now.plusSeconds(44_100)))
        return pledges.saveAndFlush(Pledge(campaign.id, 1, 200, "USD", PledgeState.CAPTURE_PENDING,
            authorizationId = "AUTH-${UUID.randomUUID()}", captureId = "CAPTURE-${UUID.randomUUID()}", createdAt = now))
    }

    private fun completedCapture(pledge: Pledge) {
        gateway.payments.readCaptureResults.add(PaymentResult.Received(
            CaptureDetails(CaptureId(pledge.captureId!!), Money(200, "USD")), PaymentStatus.COMPLETED))
    }

    private fun eventId() = "WH-${UUID.randomUUID()}".also { eventIds.add(it) }
    private fun eventCount(id: String) = jdbc.queryForObject(
        "select count(*) from paypal_webhook_events where event_id = ?", Int::class.java, id)
    private fun calls() = gateway.payments.calls.drop(firstCall)
    private fun body(id: String, resource: String, type: String = "PAYMENT.CAPTURE.COMPLETED", status: String = "COMPLETED") =
        """{"id":"$id","event_type":"$type","resource_type":"capture","resource":{"id":"$resource","status":"$status"}}"""
    private fun post(body: String) = mvc.post("/webhooks/paypal") {
        contentType = MediaType.APPLICATION_JSON
        content = body
        header("PayPal-Transmission-Sig", "synthetic-signature")
    }

    @TestConfiguration
    class Configuration {
        @Bean @Primary fun webhookGateway() = WebhookGateway()
        @Bean @Primary fun webhookClock(): Clock = Clock.fixed(now, ZoneOffset.UTC)
    }

    class WebhookGateway(val payments: InMemoryPaymentGateway = InMemoryPaymentGateway()) : PaymentGateway by payments {
        var verification = WebhookVerification.VERIFIED
        val verifications = mutableListOf<Pair<Map<String, String>, String>>()
        override fun verifyWebhook(webhookId: String, headers: Map<String, String>, eventJson: String): WebhookVerification {
            assertEquals("SYNTHETICHOOK", webhookId)
            verifications.add(headers to eventJson)
            return verification
        }
    }

    companion object {
        private val now = Instant.parse("2026-01-01T00:00:00Z")
    }
}

package app.reliability

import app.campaign.model.Campaign
import app.campaign.model.CampaignReason
import app.campaign.model.CampaignState
import app.campaign.model.Money
import app.campaign.model.Offer
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.model.Workspace
import app.campaign.port.AuthorizationDetails
import app.campaign.port.AuthorizationId
import app.campaign.port.CaptureDetails
import app.campaign.port.CaptureId
import app.campaign.port.PaymentGateway
import app.campaign.port.PaymentResult
import app.campaign.port.PaymentStatus
import app.campaign.port.RefundDetails
import app.campaign.port.RefundId
import app.campaign.port.WebhookVerification
import app.campaign.rules.PaymentAction
import app.campaign.rules.idempotencyKey
import app.campaign.service.CampaignRef
import app.campaign.service.CampaignService
import app.campaign.service.CampaignView
import app.campaign.service.MutableClock
import app.campaign.service.ServiceResult
import app.campaign.store.CampaignRepository
import app.campaign.store.EventRepository
import app.campaign.store.OfferRepository
import app.campaign.store.PledgeRepository
import app.campaign.store.WorkspaceRepository
import app.testing.InMemoryPaymentGateway
import app.testing.PaymentCall
import com.zaxxer.hikari.HikariDataSource
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
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(properties = [
    "PAYPAL_WEBHOOK_ID=SEQUENCEHOOK",
    "spring.datasource.hikari.maximum-pool-size=3",
    "spring.datasource.hikari.connection-timeout=1000",
])
@AutoConfigureMockMvc
@Import(PaymentWebhookSequenceTests.Configuration::class)
class PaymentWebhookSequenceTests @Autowired constructor(
    private val mvc: MockMvc,
    private val gateway: SequenceGateway,
    private val clock: MutableClock,
    private val service: CampaignService,
    private val workspaces: WorkspaceRepository,
    private val offers: OfferRepository,
    private val campaigns: CampaignRepository,
    private val pledges: PledgeRepository,
    private val events: EventRepository,
    private val jdbc: JdbcTemplate,
) {
    private val ownedWorkspaces = mutableListOf<UUID>()
    private val eventIds = mutableListOf<String>()
    private var firstCall = 0

    @BeforeEach
    fun reset() {
        clock.current = Instant.parse("2026-01-01T00:00:00Z")
        gateway.verification = WebhookVerification.VERIFIED
        gateway.verifiedBodies.clear()
        gateway.payments.readAuthorizationResults.clear()
        gateway.payments.readCaptureResults.clear()
        gateway.payments.refundCaptureResults.clear()
        gateway.payments.readRefundResults.clear()
        firstCall = gateway.payments.calls.size
        val pool = jdbc.dataSource!!.unwrap(HikariDataSource::class.java)
        assertEquals(3, pool.maximumPoolSize)
        assertEquals(1000L, pool.connectionTimeout)
    }

    @AfterEach
    fun clean() {
        eventIds.forEach { jdbc.update("delete from paypal_webhook_events where event_id = ?", it) }
        ownedWorkspaces.forEach { workspaceId ->
            val rows = offers.findByWorkspaceId(workspaceId)
            campaigns.findByOfferIdIn(rows.map { it.id }).forEach { campaign ->
                events.deleteAll(events.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id))
                pledges.deleteAll(pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id))
                campaigns.delete(campaign)
            }
            offers.deleteAll(rows)
            workspaces.deleteById(workspaceId)
        }
    }

    @Test
    fun `late capture refunds once despite duplicate completion and stale denial notifications`() {
        val fixture = fixture(CampaignState.NOT_FUNDED, PledgeState.CAPTURE_PENDING)
        val completion = notification(fixture)
        captureResult(fixture, PaymentStatus.COMPLETED)
        refundResult(fixture, PaymentStatus.PENDING)
        deliver(completion)
        assertEquals(PledgeState.REFUND_PENDING, stored(fixture).state)
        deliver(completion)
        assertEquals(2, calls().size)

        gateway.payments.readRefundResults.add(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE))
        deliver(notification(fixture, type = "PAYMENT.CAPTURE.DENIED", status = "DENIED"))
        val unresolved = view(fixture)
        assertEquals(0L, unresolved.counts.paid)
        assertEquals(0L, unresolved.result.refundedUnits)
        assertEquals(3, calls().size)

        clock.current = clock.current.plusSeconds(60)
        gateway.payments.readRefundResults.add(PaymentResult.Received(refund(fixture), PaymentStatus.COMPLETED))
        deliver(notification(fixture, type = "PAYMENT.CAPTURE.REFUNDED", resourceType = "refund",
            resourceId = refund(fixture).refundId.value))
        deliver(notification(fixture))
        assertRefunded(fixture)
        assertEquals(listOf(refundWrite(fixture)), writes())
        assertEquals(4, calls().size)
    }

    @Test
    fun `external void verifies the original body before mutation through the exact csrf exemption`() {
        val fixture = fixture(CampaignState.OPEN, PledgeState.AUTHORIZED)
        val original = "  " + notification(fixture, type = "PAYMENT.AUTHORIZATION.VOIDED", status = "VOIDED",
            resourceType = "authorization", resourceId = fixture.pledge.authorizationId!!) + "\n"
        mvc.post("/webhooks/paypal/") { contentType = MediaType.APPLICATION_JSON; content = original }
            .andExpect { status { isForbidden() } }
        assertTrue(gateway.verifiedBodies.isEmpty())
        gateway.verification = WebhookVerification.INVALID
        mvc.post("/webhooks/paypal") { contentType = MediaType.APPLICATION_JSON; content = original }
            .andExpect { status { isUnauthorized() } }
        assertEquals(0, receipts())
        assertTrue(calls().isEmpty())
        assertEquals(PledgeState.AUTHORIZED, stored(fixture).state)

        gateway.verification = WebhookVerification.VERIFIED
        gateway.payments.readAuthorizationResults.add(PaymentResult.Received(AuthorizationDetails(
            AuthorizationId(fixture.pledge.authorizationId!!), Money(200, "USD"), null,
        ), PaymentStatus.VOIDED))
        repeat(2) { deliver(original) }
        val resolved = view(fixture)
        assertEquals(CampaignState.OPEN, resolved.state)
        assertEquals(0L, resolved.counts.authorizedHolds)
        assertEquals(1L, resolved.result.releasedUnits)
        assertTrue(resolved.needsAttention.isEmpty())
        assertEquals(listOf(original, original, original), gateway.verifiedBodies)
        assertEquals(1, receipts())
        assertEquals(1, calls().size)
        assertTrue(writes().isEmpty())
    }

    @Test
    fun `external full refund remains final when stale completion is delivered afterward`() {
        val fixture = fixture(CampaignState.FUNDED, PledgeState.CAPTURED)
        captureResult(fixture, PaymentStatus.REFUNDED)
        val refunded = notification(fixture, type = "PAYMENT.CAPTURE.REFUNDED", resourceType = "refund",
            resourceId = refund(fixture).refundId.value)
        repeat(2) { deliver(refunded) }
        deliver(notification(fixture))
        val result = assertRefunded(fixture)
        assertEquals("FUNDED_PAYMENT_SHORTFALL", result.needsAttention.single().reason)
        assertEquals(1, calls().size)
        assertTrue(writes().isEmpty())
    }

    @Test
    fun `partial refund never restores paid units or refunds a guessed remainder on either terminal outcome`() {
        for (state in listOf(CampaignState.FUNDED, CampaignState.NOT_FUNDED)) {
            val fixture = fixture(state, PledgeState.CAPTURE_PENDING)
            if (state == CampaignState.FUNDED) {
                fixture.pledge.state = PledgeState.CAPTURED
                pledges.saveAndFlush(fixture.pledge)
            }
            val before = calls().size
            captureResult(fixture, PaymentStatus.PARTIALLY_REFUNDED)
            val partial = notification(fixture, type = "PAYMENT.CAPTURE.REFUNDED", resourceType = "refund",
                resourceId = refund(fixture).refundId.value)
            repeat(2) { deliver(partial) }
            assertEquals(PledgeState.PARTIALLY_REFUNDED, stored(fixture).state)

            captureResult(fixture, PaymentStatus.COMPLETED)
            deliver(notification(fixture))
            gateway.payments.readCaptureResults.add(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE))
            deliver(notification(fixture))
            val count = calls().size
            val unresolved = view(fixture)
            assertEquals(state, unresolved.state)
            assertEquals(0L, unresolved.counts.paid)
            assertEquals(0L, unresolved.result.refundedUnits)
            assertEquals(PledgeState.PARTIALLY_REFUNDED, unresolved.pledges.single().state)
            assertEquals(fixture.pledge.id, unresolved.needsAttention.single().pledgeId)
            assertEquals(count, calls().size)

            clock.current = clock.current.plusSeconds(60)
            captureResult(fixture, PaymentStatus.REFUNDED)
            deliver(notification(fixture))
            assertRefunded(fixture)
            assertEquals(4, calls().size - before)
        }
        assertTrue(writes().isEmpty())
    }

    @Test
    fun `external refund resolves a failed or ambiguous no ID refund without another write`() {
        for (failure in listOf(PaymentStatus.FAILED, PaymentStatus.TRANSPORT_FAILURE)) {
            val fixture = fixture(CampaignState.NOT_FUNDED, PledgeState.CAPTURE_PENDING)
            val before = calls().size
            captureResult(fixture, PaymentStatus.COMPLETED)
            gateway.payments.refundCaptureResults.add(PaymentResult.Failed(failure))
            val completion = notification(fixture)
            repeat(2) { deliver(completion) }
            assertNull(stored(fixture).refundId)
            val expected = if (failure == PaymentStatus.FAILED) PledgeState.REFUND_FAILED else PledgeState.REFUND_PENDING
            assertEquals(expected, stored(fixture).state)

            clock.current = clock.current.plusSeconds(60)
            gateway.payments.readCaptureResults.add(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE))
            deliver(notification(fixture))
            assertEquals(expected, stored(fixture).state)
            assertEquals(0L, view(fixture).result.refundedUnits)
            clock.current = clock.current.plusSeconds(60)
            captureResult(fixture, PaymentStatus.REFUNDED)
            deliver(notification(fixture, type = "PAYMENT.CAPTURE.REFUNDED", resourceType = "refund",
                resourceId = refund(fixture).refundId.value))
            deliver(notification(fixture))
            assertRefunded(fixture)
            assertEquals(listOf(refundWrite(fixture)), calls().drop(before).filterIsInstance<PaymentCall.RefundCapture>())
            assertEquals(4, calls().size - before)
        }
        assertTrue(writes().all { it is PaymentCall.RefundCapture })
    }

    @Test
    fun `confirmed capture after a no ID refund failure reuses the exact original write key`() {
        val fixture = fixture(CampaignState.NOT_FUNDED, PledgeState.CAPTURE_PENDING)
        captureResult(fixture, PaymentStatus.COMPLETED)
        gateway.payments.refundCaptureResults.add(PaymentResult.Failed(PaymentStatus.FAILED))
        deliver(notification(fixture))
        clock.current = clock.current.plusSeconds(60)
        captureResult(fixture, PaymentStatus.COMPLETED)
        refundResult(fixture, PaymentStatus.COMPLETED)
        val stale = notification(fixture, type = "PAYMENT.CAPTURE.DENIED", status = "DENIED")
        repeat(2) { deliver(stale) }
        assertRefunded(fixture)
        assertEquals(listOf(refundWrite(fixture), refundWrite(fixture)), writes())
        assertEquals(listOf(PaymentCall.ReadCapture::class, PaymentCall.RefundCapture::class,
            PaymentCall.ReadCapture::class, PaymentCall.RefundCapture::class), calls().map { it::class })
    }

    private fun fixture(state: CampaignState, pledgeState: PledgeState): Fixture {
        val now = clock.instant()
        val workspace = workspaces.saveAndFlush(Workspace("sequence-${UUID.randomUUID()}", now))
        ownedWorkspaces.add(workspace.id)
        val offer = offers.saveAndFlush(Offer(workspace.id, UUID.randomUUID(), UUID.randomUUID(), "Print", "A5 print",
            emptyMap(), 200, "USD", "Shipping included", "US", 1, 3))
        val campaign = campaigns.saveAndFlush(Campaign(offer.id, state,
            reason = if (state == CampaignState.NOT_FUNDED) CampaignReason.CAPTURE_SHORTFALL else null,
            openedAt = now, closesAt = now.plusSeconds(900), confirmDeadline = now.plusSeconds(44_100)))
        val pledge = pledges.saveAndFlush(Pledge(campaign.id, 1, 200, "USD", pledgeState,
            authorizationId = "AUTH-${UUID.randomUUID()}",
            captureId = if (pledgeState == PledgeState.AUTHORIZED) null else "CAP-${UUID.randomUUID()}", createdAt = now))
        return Fixture(CampaignRef(workspace.id, campaign.id), pledge, state)
    }

    private fun stored(fixture: Fixture) = pledges.findById(fixture.pledge.id).orElseThrow()
    private fun view(fixture: Fixture) = assertIs<ServiceResult.Success<CampaignView>>(service.readCampaign(fixture.ref)).value
    private fun assertRefunded(fixture: Fixture): CampaignView = view(fixture).also {
        assertEquals(fixture.state, it.state)
        assertEquals(PledgeState.REFUNDED, it.pledges.single().state)
        assertEquals(0L, it.counts.paid)
        assertEquals(1L, it.result.refundedUnits)
        if (fixture.state == CampaignState.NOT_FUNDED) assertTrue(it.needsAttention.isEmpty())
    }
    private fun captureResult(fixture: Fixture, status: PaymentStatus) {
        gateway.payments.readCaptureResults.add(PaymentResult.Received(CaptureDetails(
            CaptureId(fixture.pledge.captureId!!), Money(200, "USD"), AuthorizationId(fixture.pledge.authorizationId!!),
        ), status))
    }
    private fun refund(fixture: Fixture) = RefundDetails(RefundId("REFUND-${fixture.pledge.id}"), Money(200, "USD"),
        CaptureId(fixture.pledge.captureId!!))
    private fun refundResult(fixture: Fixture, status: PaymentStatus) {
        gateway.payments.refundCaptureResults.add(PaymentResult.Received(refund(fixture), status))
    }
    private fun refundWrite(fixture: Fixture) = PaymentCall.RefundCapture(CaptureId(fixture.pledge.captureId!!),
        idempotencyKey(fixture.pledge.id, PaymentAction.REFUND))
    private fun calls() = gateway.payments.calls.drop(firstCall)
    private fun writes() = calls().filter { it is PaymentCall.Capture || it is PaymentCall.RefundCapture ||
        it is PaymentCall.VoidAuthorization || it is PaymentCall.AuthorizeOrder || it is PaymentCall.CreateOrder }
    private fun receipts() = eventIds.sumOf { id -> jdbc.queryForObject(
        "select count(*) from paypal_webhook_events where event_id = ?", Int::class.java, id)!! }
    private fun notification(fixture: Fixture, type: String = "PAYMENT.CAPTURE.COMPLETED", status: String = "COMPLETED",
        resourceType: String = "capture", resourceId: String = fixture.pledge.captureId!!): String {
        val id = "WH-${UUID.randomUUID()}".also { eventIds.add(it) }
        val related = if (resourceType == "refund")
            ",\"supplementary_data\":{\"related_ids\":{\"capture_id\":\"${fixture.pledge.captureId}\"}}" else ""
        return """{"id":"$id","event_type":"$type","resource_type":"$resourceType",
            "resource":{"id":"$resourceId","status":"$status"$related}}"""
    }
    private fun deliver(body: String) {
        mvc.post("/webhooks/paypal") {
            contentType = MediaType.APPLICATION_JSON
            content = body
            header("PayPal-Transmission-Sig", "synthetic-signature")
        }.andExpect {
            status { isOk() }
            header { string("X-Content-Type-Options", "nosniff") }
        }
    }

    private data class Fixture(val ref: CampaignRef, val pledge: Pledge, val state: CampaignState)

    @TestConfiguration
    class Configuration {
        @Bean @Primary fun sequenceGateway() = SequenceGateway()
        @Bean @Primary fun sequenceClock() = MutableClock()
    }

    class SequenceGateway(val payments: InMemoryPaymentGateway = InMemoryPaymentGateway()) : PaymentGateway by payments {
        var verification = WebhookVerification.VERIFIED
        val verifiedBodies = mutableListOf<String>()
        override fun verifyWebhook(webhookId: String, headers: Map<String, String>, eventJson: String): WebhookVerification {
            assertEquals("SEQUENCEHOOK", webhookId)
            verifiedBodies.add(eventJson)
            return verification
        }
    }
}

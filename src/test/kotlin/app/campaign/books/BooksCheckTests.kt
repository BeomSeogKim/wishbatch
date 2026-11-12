package app.campaign.books

import app.campaign.model.Campaign
import app.campaign.model.CampaignState
import app.campaign.model.Event
import app.campaign.model.Money
import app.campaign.model.Offer
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.port.AuthorizationDetails
import app.campaign.port.AuthorizationId
import app.campaign.port.CaptureDetails
import app.campaign.port.CaptureId
import app.campaign.port.OrderDetails
import app.campaign.port.OrderId
import app.campaign.port.PaymentGateway
import app.campaign.port.PaymentResult
import app.campaign.port.PaymentStatus
import app.campaign.port.RefundDetails
import app.campaign.port.RefundId
import app.campaign.service.CampaignRef
import app.campaign.service.CampaignService
import app.campaign.service.ServiceResult
import app.campaign.store.CampaignRepository
import app.campaign.store.EventRepository
import app.campaign.store.OfferRepository
import app.campaign.store.PledgeRepository
import app.campaign.workspace.WorkspaceService
import app.testing.InMemoryPaymentGateway
import app.testing.fixedClock
import app.web.security.securePost
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(BooksCheckTests.Fakes::class)
class BooksCheckTests @Autowired constructor(
    private val books: BooksCheckService,
    private val service: CampaignService,
    private val workspaces: WorkspaceService,
    private val offers: OfferRepository,
    private val campaigns: CampaignRepository,
    private val pledges: PledgeRepository,
    private val events: EventRepository,
    private val gateway: BooksGateway,
    private val mvc: MockMvc,
) {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val ownedCampaigns = mutableListOf<UUID>()

    @BeforeEach
    fun reset() = gateway.reset()

    @AfterEach
    fun cleanCampaigns() {
        ownedCampaigns.forEach { id ->
            val campaign = campaigns.findById(id).orElseThrow()
            events.deleteAll(events.findByCampaignIdOrderByCreatedAtAscIdAsc(id))
            pledges.deleteAll(pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(id))
            campaigns.delete(campaign)
            offers.deleteById(campaign.offerId)
        }
        ownedCampaigns.clear()
    }

    @Test
    fun `explicit check heals external void through existing transitions and persists private free history`() {
        val fixture = fixture(PledgeState.AUTHORIZED)
        gateway.authorizationStatus = PaymentStatus.VOIDED
        events.save(Event(fixture.ref.campaignId, "EXISTING_EVENT", createdAt = now.minusSeconds(10)))

        val check = success(books.check(fixture.ref))
        val row = check.pledges.single()
        assertEquals(BooksMatch.MISMATCH, row.before.match)
        assertEquals(PledgeState.AUTHORIZED, row.before.state)
        assertEquals(BooksMatch.MATCH, row.after.match)
        assertEquals(PledgeState.VOIDED, row.after.state)
        assertEquals(PledgeState.VOIDED, pledges.findById(fixture.pledge.id).orElseThrow().state)
        val reads = gateway.calls.size
        assertEquals(check, success(books.history(fixture.ref)).single())
        assertEquals(reads, gateway.calls.size)
        val timeline = events.findByCampaignIdOrderByCreatedAtAscIdAsc(fixture.ref.campaignId)
        assertTrue(timeline.any { it.type == "EXISTING_EVENT" })
        assertTrue(timeline.any { it.type == "PLEDGE_STATE" })
        val stored = timeline.single { it.type == "BOOKS_CHECK" }
        assertFalse(requireNotNull(stored.detail).contains("buyer@example.test"))
        assertFalse(requireNotNull(stored.detail).contains("Private address"))
        assertEquals(2, timeline.count { it.type == "BOOKS_RESOURCE_READ" && it.paypalEndpoint?.contains("checkout/orders") == true })
    }

    @Test
    fun `partial refund remains explicit and funded historical while only read operations run`() {
        val fixture = fixture(PledgeState.CAPTURED, CampaignState.FUNDED)
        gateway.authorizationStatus = PaymentStatus.CAPTURED
        gateway.captureStatus = PaymentStatus.PARTIALLY_REFUNDED

        val check = success(books.check(fixture.ref))
        assertEquals(BooksMatch.MISMATCH, check.pledges.single().before.match)
        assertEquals(PledgeState.PARTIALLY_REFUNDED, check.pledges.single().after.state)
        assertEquals(BooksMatch.MATCH, check.pledges.single().after.match)
        val view = success(service.readCampaign(fixture.ref))
        assertEquals(CampaignState.FUNDED, view.state)
        assertEquals(0L, view.counts.paid)
        assertEquals(0L, view.result.refundedUnits)
        assertTrue(view.needsAttention.isNotEmpty())
        assertTrue(gateway.calls.all { it.startsWith("read") })
    }

    @Test
    fun `partial capture and its completed partial refund can agree without claiming a remaining balance`() {
        val resources = listOf(
            BooksResource(BooksResourceKind.ORDER, "order", PaymentStatus.COMPLETED, "COMPLETED", 600, "USD", true),
            BooksResource(BooksResourceKind.AUTHORIZATION, "authorization", PaymentStatus.CAPTURED, null, 600, "USD", true),
            BooksResource(BooksResourceKind.CAPTURE, "capture", PaymentStatus.PARTIALLY_REFUNDED, null, 600, "USD", true),
            BooksResource(BooksResourceKind.REFUND, "refund", PaymentStatus.COMPLETED, null, 200, "USD", true),
        )
        assertEquals(BooksMatch.MATCH, booksMatch(PledgeState.PARTIALLY_REFUNDED, resources))
        assertEquals(BooksMatch.MISMATCH, booksMatch(PledgeState.REFUNDED, resources))
    }

    @Test
    fun `refund completion reads all four resource kinds and repairs pending state`() {
        val fixture = fixture(PledgeState.REFUND_PENDING, CampaignState.NOT_FUNDED)
        gateway.authorizationStatus = PaymentStatus.CAPTURED
        gateway.captureStatus = PaymentStatus.REFUNDED
        gateway.refundStatus = PaymentStatus.COMPLETED
        val check = success(books.check(fixture.ref))
        assertEquals(PledgeState.REFUNDED, check.pledges.single().after.state)
        assertEquals(BooksMatch.MATCH, check.pledges.single().after.match)
        assertEquals(BooksResourceKind.entries.toSet(), check.pledges.single().after.resources.map { it.kind }.toSet())
        assertTrue(gateway.calls.all { it.startsWith("read") })
    }

    @Test
    fun `any provider failure is unknown even when local state appears consistent`() {
        val fixture = fixture(PledgeState.AUTHORIZED)
        gateway.failOrder = true
        val check = success(books.check(fixture.ref))
        assertEquals(BooksMatch.UNKNOWN, check.pledges.single().before.match)
        assertEquals(BooksMatch.UNKNOWN, check.pledges.single().after.match)
        assertEquals(PledgeState.AUTHORIZED, check.pledges.single().after.state)
    }

    @Test
    fun `wrong provider amount or resource identity never matches`() {
        val fixture = fixture(PledgeState.AUTHORIZED)
        gateway.amount = Money(601, "USD")
        assertEquals(BooksMatch.MISMATCH, success(books.check(fixture.ref)).pledges.single().after.match)
        gateway.amount = Money(600, "USD")
        gateway.returnedOrderId = "different-order"
        assertEquals(BooksMatch.MISMATCH, success(books.check(fixture.ref)).pledges.single().after.match)
    }

    @Test
    fun `completed order alone does not prove authorization or payment`() {
        val fixture = fixture(PledgeState.CREATED)
        gateway.orderStatus = PaymentStatus.COMPLETED
        val row = success(books.check(fixture.ref)).pledges.single()
        assertEquals(BooksMatch.MISMATCH, row.after.match)
        assertEquals(PledgeState.CREATED, row.after.state)
    }

    @Test
    fun `foreign workspace is rejected before any provider calls or history access`() {
        val fixture = fixture(PledgeState.AUTHORIZED)
        val foreign = workspaces.create()
        assertIs<ServiceResult.Rejected>(books.check(CampaignRef(foreign.id, fixture.ref.campaignId)))
        assertIs<ServiceResult.Rejected>(books.history(CampaignRef(foreign.id, fixture.ref.campaignId)))
        assertTrue(gateway.calls.isEmpty())
        mvc.get("/w/${foreign.token}/maker/books?campaignId=${fixture.ref.campaignId}").andExpect { status { isNotFound() } }
    }

    @Test
    fun `maker action renders stored comparisons server table history and sandbox notice`() {
        val fixture = fixture(PledgeState.AUTHORIZED)
        val path = "/w/${fixture.token}/maker/books"
        mvc.get("$path?campaignId=${fixture.ref.campaignId}").andExpect { status { isOk() } }
            .andReturn().response.contentAsString.let { assertContains(it, "No check recorded yet.") }
        assertTrue(gateway.calls.isEmpty())
        val response = mvc.securePost(path) { param("campaignId", fixture.ref.campaignId.toString()) }
            .andExpect { status { is3xxRedirection() } }.andReturn().response
        val reads = gateway.calls.size
        val html = mvc.get(requireNotNull(response.redirectedUrl)).andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertContains(html, "books-table")
        assertContains(html, "USD 6.00")
        assertContains(html, "MATCH")
        assertContains(html, "Check history")
        assertContains(html, "Sandbox demo. No real money moves.")
        assertContains(html, "/js/books-ledger.js")
        val bundle = "https://cdn.jsdelivr.net/npm/ag-grid-community@36.3.0/dist/ag-grid-community.min.noStyle.js"
        assertContains(html, "<script defer src=\"$bundle\">")
        assertTrue(html.indexOf(bundle) < html.indexOf("/js/books-ledger.js"))
        assertFalse(html.contains("buyer@example.test"))
        assertEquals(reads, gateway.calls.size)
        mvc.get("/js/books-ledger.js").andExpect { status { isOk() } }
    }

    private fun fixture(state: PledgeState, campaignState: CampaignState = CampaignState.OPEN): Fixture {
        val workspace = workspaces.create()
        val option = workspaces.listOptions(workspace.token).first()
        val offer = offers.saveAndFlush(Offer(workspace.id, option.id, UUID.randomUUID(), "Cards", "Printed cards",
            emptyMap(), 200, "USD", "Ships later", "US", 3, 10))
        val campaign = campaigns.saveAndFlush(Campaign(offer.id, campaignState, openedAt = now,
            closesAt = now.plusSeconds(900), confirmDeadline = now.plusSeconds(44100)))
        ownedCampaigns.add(campaign.id)
        val pledge = pledges.saveAndFlush(Pledge(campaign.id, 3, 600, "USD", state,
            paypalOrderId = "order", authorizationId = if (state == PledgeState.CREATED) null else "authorization",
            captureId = if (state in setOf(PledgeState.CAPTURED, PledgeState.REFUND_PENDING)) "capture" else null,
            refundId = if (state == PledgeState.REFUND_PENDING) "refund" else null, createdAt = now))
        gateway.pledge = pledge
        return Fixture(CampaignRef(workspace.id, campaign.id), pledge, workspace.token)
    }

    private fun <T> success(result: ServiceResult<T>): T = assertIs<ServiceResult.Success<T>>(result).value
    private data class Fixture(val ref: CampaignRef, val pledge: Pledge, val token: String)

    @TestConfiguration
    class Fakes {
        @Bean @Primary fun booksGateway() = BooksGateway()
        @Bean @Primary fun booksClock() = fixedClock(Instant.parse("2026-01-01T00:00:00Z"))
    }
}

class BooksGateway : PaymentGateway by InMemoryPaymentGateway() {
    lateinit var pledge: Pledge
    var authorizationStatus = PaymentStatus.CREATED
    var captureStatus = PaymentStatus.COMPLETED
    var refundStatus = PaymentStatus.PENDING
    var orderStatus = PaymentStatus.COMPLETED
    var amount = Money(600, "USD")
    var failOrder = false
    var returnedOrderId: String? = null
    val calls = mutableListOf<String>()

    fun reset() {
        authorizationStatus = PaymentStatus.CREATED
        captureStatus = PaymentStatus.COMPLETED
        refundStatus = PaymentStatus.PENDING
        orderStatus = PaymentStatus.COMPLETED
        amount = Money(600, "USD")
        failOrder = false
        returnedOrderId = null
        calls.clear()
    }

    override fun readOrder(orderId: OrderId, idempotencyKey: String): PaymentResult<OrderDetails> {
        calls += "readOrder"
        if (failOrder) return PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE)
        return PaymentResult.Received(OrderDetails(OrderId(returnedOrderId ?: orderId.value), orderStatus.name,
            listOfNotNull(pledge.authorizationId?.let(::AuthorizationId)),
            listOfNotNull(pledge.captureId?.let(::CaptureId)), listOfNotNull(pledge.refundId?.let(::RefundId)), amount), orderStatus)
    }

    override fun readAuthorization(authorizationId: AuthorizationId, idempotencyKey: String): PaymentResult<AuthorizationDetails> {
        calls += "readAuthorization"
        return PaymentResult.Received(AuthorizationDetails(authorizationId, amount, null, OrderId("order"),
            "buyer@example.test", captureId = pledge.captureId?.let(::CaptureId)), authorizationStatus)
    }

    override fun readCapture(captureId: CaptureId, idempotencyKey: String): PaymentResult<CaptureDetails> {
        calls += "readCapture"
        return PaymentResult.Received(CaptureDetails(captureId, amount, AuthorizationId("authorization"), "buyer@example.test"), captureStatus)
    }

    override fun readRefund(refundId: RefundId, idempotencyKey: String): PaymentResult<RefundDetails> {
        calls += "readRefund"
        return PaymentResult.Received(RefundDetails(refundId, amount, CaptureId("capture")), refundStatus)
    }
}

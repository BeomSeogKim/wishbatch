package app.web.fan

import app.campaign.model.AnalysisRun
import app.campaign.model.Campaign
import app.campaign.model.CampaignReason
import app.campaign.model.CampaignState
import app.campaign.model.Event
import app.campaign.model.Money
import app.campaign.model.Offer
import app.campaign.model.Option
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.model.Wish
import app.campaign.model.WishSource
import app.campaign.model.WishKind
import app.campaign.model.Workspace
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalyzedWish
import app.campaign.port.AuthorizationDetails
import app.campaign.port.AuthorizationId
import app.campaign.port.OrderDetails
import app.campaign.port.OrderId
import app.campaign.port.PaymentResult
import app.campaign.port.PaymentStatus
import app.campaign.port.Proposal
import app.campaign.port.VoidDetails
import app.campaign.provenance.DecisionRecordService
import app.campaign.provenance.DecisionRecordQuery
import app.campaign.service.CampaignPersistence
import app.campaign.service.CampaignRef
import app.campaign.service.CampaignQueries
import app.campaign.service.OfferEvidenceQuery
import app.campaign.service.PledgeRef
import app.campaign.service.PledgeReferences
import app.campaign.service.Rejection
import app.campaign.service.ServiceResult
import app.campaign.store.AnalysisRunRepository
import app.campaign.store.CampaignRepository
import app.campaign.store.EventRepository
import app.campaign.store.OfferRepository
import app.campaign.store.OptionRepository
import app.campaign.store.PledgeRepository
import app.campaign.store.WishRepository
import app.campaign.store.WorkspaceRepository
import app.campaign.terms.TermsSealQuery
import app.campaign.workspace.WorkspaceService
import app.paypal.PayPalProperties
import app.testing.InMemoryPaymentGateway
import app.testing.PaymentCall
import app.web.security.securePost
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.ui.ExtendedModelMap
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

@SpringBootTest(properties = ["PAYPAL_CLIENT_ID=synthetic-client", "PAYPAL_CLIENT_SECRET=synthetic-secret"])
@AutoConfigureMockMvc
@Import(FanPaymentControllerTests.PaymentConfiguration::class)
class FanPaymentControllerTests @Autowired constructor(
    private val mvc: MockMvc,
    private val workspaces: WorkspaceRepository,
    private val options: OptionRepository,
    private val analyses: AnalysisRunRepository,
    private val offers: OfferRepository,
    private val campaigns: CampaignRepository,
    private val pledges: PledgeRepository,
    private val wishes: WishRepository,
    private val events: EventRepository,
    private val gateway: InMemoryPaymentGateway,
    private val clock: FanPaymentClock,
    private val mapper: ObjectMapper,
    private val messages: MessageSource,
    private val references: PledgeReferences,
    private val workspaceService: WorkspaceService,
    private val queries: CampaignQueries,
    private val evidence: OfferEvidenceQuery,
    private val terms: TermsSealQuery,
    private val records: DecisionRecordService,
    private val recordQuery: DecisionRecordQuery,
    private val persistence: CampaignPersistence,
    private val entities: EntityManager,
) {
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val ownedWorkspaces = mutableListOf<UUID>()
    private var firstCall = 0

    @BeforeEach
    fun resetTimeAndScripts() {
        clock.current = start
        firstCall = gateway.calls.size
        gateway.createOrderResults.clear()
        gateway.authorizeOrderResults.clear()
        gateway.readAuthorizationResults.clear()
        gateway.voidAuthorizationResults.clear()
    }

    @AfterEach
    fun removeFixtures() {
        ownedWorkspaces.forEach { workspaceId ->
            persistence.checkpoint {
                entities.createQuery("delete from DecisionSourceSnapshot s where s.workspaceId = :id and s.sourceType = 'OFFER'")
                    .setParameter("id", workspaceId).executeUpdate()
            }
            val workspaceOffers = offers.findByWorkspaceId(workspaceId)
            campaigns.findByOfferIdIn(workspaceOffers.map { it.id }).forEach { campaign ->
                events.deleteAll(events.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id))
                pledges.deleteAll(pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id))
                campaigns.delete(campaign)
            }
            offers.deleteAll(workspaceOffers)
            analyses.deleteAll(analyses.findByWorkspaceId(workspaceId))
            options.deleteAll(options.findByWorkspaceId(workspaceId))
            wishes.deleteAll(wishes.findByWorkspaceId(workspaceId))
            workspaces.deleteById(workspaceId)
        }
    }

    @Test
    fun `order ignores client amount and authorization returns an unguessable scoped status URL`() {
        val fixture = fixture()
        gateway.createOrderResults.add(received(OrderDetails(OrderId("synthetic-order")), PaymentStatus.CREATED))
        val response = mvc.securePost("${fixture.path}/api/pledges") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"quantity":2,"amount":1,"currency":"EUR"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.outcome") { value("CREATED") }
            jsonPath("$.orderId") { value("synthetic-order") }
            jsonPath("$.state") { value("CREATED") }
        }.andReturn().response.contentAsString
        val body = mapper.readTree(response)
        val id = UUID.fromString(body["pledgeId"].asString())
        assertEquals(4, id.version())
        assertEquals("${fixture.path}/pledges/$id", body["statusUrl"].asString())
        val call = gateway.calls.drop(firstCall).filterIsInstance<PaymentCall.CreateOrder>().single()
        assertEquals(Money(400, "USD"), call.request.amount)
        assertEquals(id, call.request.pledgeId)
        assertEquals(PledgeState.CREATED, pledges.findById(id).orElseThrow().state)
        assertNull(pledges.findById(id).orElseThrow().wishId)
        val statusHtml = mvc.get("${fixture.path}/pledges/$id").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertContains(statusHtml, "${fixture.path}/pledges/$id/trace")
        assertContains(statusHtml, requireNotNull(recordQuery.findPledgeByToken(fixture.workspace.token, id)?.lastHash))

        gateway.authorizeOrderResults.add(received(AuthorizationDetails(
            AuthorizationId("synthetic-authorization"), Money(400, "USD"), start.plusSeconds(86_400),
            orderId = OrderId("synthetic-order"), payerEmail = "private-buyer@example.test",
        ), PaymentStatus.CREATED))
        val approved = mvc.securePost("${fixture.path}/api/pledges/$id/authorize").andExpect {
            status { isOk() }
            jsonPath("$.outcome") { value("AUTHORIZED") }
            jsonPath("$.state") { value("AUTHORIZED") }
            jsonPath("$.statusUrl") { value("${fixture.path}/pledges/$id") }
        }.andReturn().response.contentAsString
        assertFalse(approved.contains("private-buyer"))
        assertFalse(approved.contains("example.test"))
        assertEquals(PledgeState.AUTHORIZED, pledges.findById(id).orElseThrow().state)
    }

    @Test
    fun `explicit wish wins over session and session wish is used when the request omits it`() {
        val fixture = fixture()
        val sessionWish = wishes.saveAndFlush(Wish(fixture.workspace.id, "A small moon print", WishSource.ONSITE,
            createdAt = start))
        val explicitWish = wishes.saveAndFlush(Wish(fixture.workspace.id, "A blue moon print", WishSource.ONSITE,
            createdAt = start))
        val session = MockHttpSession().apply { setAttribute("followup.wish.${fixture.workspace.token}", sessionWish.id) }
        for (explicit in listOf(false, true)) {
            gateway.createOrderResults.add(received(OrderDetails(OrderId("synthetic-order-$explicit")), PaymentStatus.CREATED))
            val wishField = if (explicit) ",\"wishId\":\"${explicitWish.id}\"" else ""
            val response = mvc.securePost("${fixture.path}/api/pledges", session) {
                contentType = MediaType.APPLICATION_JSON
                content = """{"quantity":1$wishField}"""
            }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
            val pledgeId = UUID.fromString(mapper.readTree(response)["pledgeId"].asString())
            assertEquals(if (explicit) explicitWish.id else sessionWish.id, pledges.findById(pledgeId).orElseThrow().wishId)
        }
    }

    @Test
    fun `foreign and missing wish references reject before a payment or pledge is created`() {
        val fixture = fixture()
        val other = fixture()
        val foreignWish = wishes.saveAndFlush(Wish(other.workspace.id, "A different workspace wish", WishSource.ONSITE,
            createdAt = start))
        for (wishId in listOf(foreignWish.id, UUID.randomUUID())) {
            mvc.securePost("${fixture.path}/api/pledges") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"quantity":1,"wishId":"$wishId"}"""
            }.andExpect { status { isNotFound() } }
            mvc.get("${fixture.path}/offer") { param("wishId", wishId.toString()) }
                .andExpect { status { isNotFound() } }
        }
        assertTrue(pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(fixture.campaign.id).isEmpty())
        assertTrue(gateway.calls.drop(firstCall).isEmpty())
    }

    @Test
    fun `offer keeps an explicit or session wish in its model and wish links carry the reference`() {
        val fixture = fixture()
        val wish = wishes.saveAndFlush(Wish(fixture.workspace.id, "A print for my desk", WishSource.ONSITE, createdAt = start))
        val session = MockHttpSession().apply { setAttribute("followup.wish.${fixture.workspace.token}", wish.id) }
        val offerHtml = mvc.get("${fixture.path}/offer") { param("wishId", wish.id.toString()) }
            .andExpect { status { isOk() }; model { attribute("wishId", wish.id) } }
            .andReturn().response.contentAsString
        assertContains(offerHtml, "data-wish-id=\"${wish.id}\"")
        assertContains(offerHtml, "name=\"wishId\" value=\"${wish.id}\"")
        mvc.get("${fixture.path}/offer") { this.session = session }
            .andExpect { status { isOk() }; model { attribute("wishId", wish.id) } }
        val html = mvc.get(fixture.path) { this.session = session }.andReturn().response.contentAsString
        assertContains(html, "${fixture.path}/offer?wishId=${wish.id}")
    }

    @Test
    fun `invalid quantity capacity and closed campaigns reject order creation before PayPal`() {
        val fixture = fixture(capacity = 3)
        for (quantity in listOf(0, -1, 4)) {
            mvc.securePost("${fixture.path}/api/pledges") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"quantity":$quantity}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.reason") { value("INVALID_QUANTITY") }
            }
        }
        pledge(fixture, PledgeState.AUTHORIZED, quantity = 3)
        mvc.securePost("${fixture.path}/api/pledges") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"quantity":1}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.reason") { value("CAPACITY_EXCEEDED") }
        }
        val closed = fixture(state = CampaignState.NOT_FUNDED, reason = CampaignReason.CANCELLED_BY_MAKER)
        mvc.securePost("${closed.path}/api/pledges") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"quantity":1}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.reason") { value("INVALID_STATE") }
        }
        assertTrue(gateway.calls.drop(firstCall).isEmpty())
    }

    @Test
    fun `stale and foreign campaign IDs cannot authorize a replacement offer`() {
        val fixture = fixture()
        val other = fixture()
        val old = campaigns.saveAndFlush(Campaign(fixture.campaign.offerId, CampaignState.NOT_FUNDED,
            CampaignReason.CANCELLED_BY_MAKER, openedAt = start.minusSeconds(900), closesAt = start,
            confirmDeadline = start.plusSeconds(43_200)))
        for ((id, reason, expectedStatus) in listOf(
            Triple(old.id, "INVALID_STATE", 409), Triple(other.campaign.id, "NOT_FOUND", 404),
        )) {
            mvc.securePost("${fixture.path}/api/pledges") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"quantity":1,"campaignId":"$id"}"""
            }.andExpect {
                status { isEqualTo(expectedStatus) }
                jsonPath("$.reason") { value(reason) }
            }
        }
        assertTrue(pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(fixture.campaign.id).isEmpty())
        assertTrue(gateway.calls.drop(firstCall).isEmpty())
    }

    @Test
    fun `capacity lost during approval releases the hold and reports the actual state`() {
        val fixture = fixture(capacity = 1)
        val pending = pledge(fixture, PledgeState.CREATED)
        pledge(fixture, PledgeState.AUTHORIZED)
        scriptAuthorization(pending)
        gateway.voidAuthorizationResults.add(received(VoidDetails(AuthorizationId("authorization-${pending.id}")), PaymentStatus.VOIDED))
        val response = mvc.securePost("${fixture.path}/api/pledges/${pending.id}/authorize").andExpect {
            status { isConflict() }
            jsonPath("$.reason") { value("CAPACITY_EXCEEDED") }
            jsonPath("$.state") { value("VOIDED") }
        }.andReturn().response.contentAsString
        assertContains(response, "Hold released")
        assertEquals(PledgeState.VOIDED, pledges.findById(pending.id).orElseThrow().state)
        assertEquals(1, gateway.calls.drop(firstCall).filterIsInstance<PaymentCall.VoidAuthorization>().size)
    }

    @Test
    fun `approval received after closing releases the authorization instead of counting it`() {
        val fixture = fixture()
        val pending = pledge(fixture, PledgeState.CREATED)
        clock.current = start.plusSeconds(901)
        scriptAuthorization(pending)
        gateway.voidAuthorizationResults.add(received(VoidDetails(AuthorizationId("authorization-${pending.id}")), PaymentStatus.VOIDED))
        mvc.securePost("${fixture.path}/api/pledges/${pending.id}/authorize").andExpect {
            status { isConflict() }
            jsonPath("$.reason") { value("INVALID_STATE") }
            jsonPath("$.state") { value("VOIDED") }
        }
        assertEquals(PledgeState.VOIDED, pledges.findById(pending.id).orElseThrow().state)
    }

    @Test
    fun `provider amount mismatch is released and failed release never claims success`() {
        for (voidSucceeds in listOf(true, false)) {
            val fixture = fixture()
            val pending = pledge(fixture, PledgeState.CREATED)
            scriptAuthorization(pending, amount = Money(1, "USD"))
            gateway.voidAuthorizationResults.add(if (voidSucceeds) {
                received(VoidDetails(AuthorizationId("authorization-${pending.id}")), PaymentStatus.VOIDED)
            } else PaymentResult.Failed(PaymentStatus.FAILED))
            val response = mvc.securePost("${fixture.path}/api/pledges/${pending.id}/authorize").andExpect {
                status { isConflict() }
                jsonPath("$.reason") { value("PAYMENT_MISMATCH") }
                jsonPath("$.state") { value(if (voidSucceeds) "VOIDED" else "VOID_FAILED") }
            }.andReturn().response.contentAsString
            if (!voidSucceeds) assertFalse(response.contains("Hold released"))
        }
    }

    @Test
    fun `unavailable provider returns a typed error and leaves the pledge uncounted`() {
        val fixture = fixture()
        repeat(4) { gateway.createOrderResults.add(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE)) }
        mvc.securePost("${fixture.path}/api/pledges") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"quantity":1}"""
        }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.outcome") { value("REJECTED") }
            jsonPath("$.reason") { value("PAYMENT_UNAVAILABLE") }
        }
        assertEquals(PledgeState.CREATED, pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(fixture.campaign.id).single().state)
    }

    @Test
    fun `unknown workspaces and unknown foreign or malformed pledge IDs reveal no payment data`() {
        val fixture = fixture()
        val other = fixture()
        val privatePledge = pledge(other, PledgeState.AUTHORIZED)
        for (id in listOf(UUID.randomUUID().toString(), privatePledge.id.toString(), "not-a-uuid")) {
            mvc.get("${fixture.path}/pledges/$id").andExpect {
                status { isNotFound() }
                view { name("error/not-found") }
            }
            mvc.securePost("${fixture.path}/api/pledges/$id/authorize").andExpect {
                status { isNotFound() }
                jsonPath("$.reason") { value("NOT_FOUND") }
            }
        }
        for (suffix in listOf("offer", "result", "pledges/${privatePledge.id}")) {
            mvc.get("/w/missing/$suffix").andExpect {
                status { isNotFound() }
                view { name("error/not-found") }
            }
        }
        mvc.securePost("/w/missing/api/pledges") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"quantity":1}"""
        }.andExpect { status { isNotFound() }; jsonPath("$.reason") { value("NOT_FOUND") } }
        assertTrue(gateway.calls.drop(firstCall).isEmpty())
    }

    @Test
    fun `pledge reference checks ownership without reconciling an expired campaign`() {
        val fixture = fixture()
        val other = fixture()
        val pending = pledge(fixture, PledgeState.CREATED)
        clock.current = start.plusSeconds(901)
        val ref = assertIs<ServiceResult.Success<*>>(references.find(fixture.workspace.token, pending.id)).value
        assertEquals(PledgeRef(CampaignRef(fixture.workspace.id, fixture.campaign.id), pending.id), ref)
        for ((token, id) in listOf(other.workspace.token to pending.id, "unknown" to pending.id,
            fixture.workspace.token to UUID.randomUUID())) {
            assertEquals(Rejection.NOT_FOUND, assertIs<ServiceResult.Rejected>(references.find(token, id)).reason)
        }
        assertEquals(CampaignState.OPEN, campaigns.findById(fixture.campaign.id).orElseThrow().state)
        assertEquals(PledgeState.CREATED, pledges.findById(pending.id).orElseThrow().state)
        assertTrue(events.findByCampaignIdOrderByCreatedAtAscIdAsc(fixture.campaign.id).isEmpty())
        assertTrue(gateway.calls.drop(firstCall).isEmpty())
    }

    @Test
    fun `offer renders exact terms counts and server total with six terms above PayPal`() {
        val fixture = fixture()
        pledge(fixture, PledgeState.CREATED)
        val hold = pledge(fixture, PledgeState.AUTHORIZED)
        scriptHoldRead(hold)
        val html = mvc.get("${fixture.path}/offer") { param("quantity", "2") }.andExpect {
            status { isOk() }
            view { name("fan/offer") }
        }.andReturn().response.contentAsString
        for (text in listOf("Moon print", "A5 archival print", "Shipping included", "US", "USD", "4.00",
            "2026-01-01T00:15:00Z", "2026-01-01T12:15:00Z", "Interested in this offer", "Authorized holds", "Paid",
            "Sandbox demo. No real money moves.", "synthetic-client", "intent=authorize")) assertContains(html, text)
        assertFalse(html.contains("synthetic-secret"))
        assertFalse(html.contains("private-buyer"))
        assertContains(html, "<svg")
        assertContains(html, "<title id=\"outcome-svg-title\">Payment outcome map</title>")
        assertContains(html, "attempts to refund completed payments and release remaining holds")
        assertFalse(html.contains("aria-current=\"step\""))
        val buttonPosition = html.indexOf("id=\"paypal-button-container\"")
        assertTrue(buttonPosition > 0)
        for (number in 1..6) {
            val term = messages.getMessage("money.term.$number", null, Locale.ENGLISH)
            val position = html.indexOf(term)
            assertTrue(position in 0 until buttonPosition, "Money term $number must appear before the PayPal action")
        }
        assertContains(mvc.get(fixture.path).andReturn().response.contentAsString, "href=\"${fixture.path}/offer\"")
        assertIs<PaymentCall.ReadAuthorization>(gateway.calls.drop(firstCall).single())
    }

    @Test
    fun `invalid or unavailable quote renders no payment action`() {
        val fixture = fixture(capacity = 1)
        for (quantity in listOf("0", "2")) {
            val html = mvc.get("${fixture.path}/offer") { param("quantity", quantity) }
                .andReturn().response.contentAsString
            assertFalse(html.contains("id=\"paypal-button-container\""))
        }
        val hold = pledge(fixture, PledgeState.AUTHORIZED)
        scriptHoldRead(hold)
        val html = mvc.get("${fixture.path}/offer").andReturn().response.contentAsString
        assertFalse(html.contains("id=\"paypal-button-container\""))
        assertIs<PaymentCall.ReadAuthorization>(gateway.calls.drop(firstCall).single())
    }

    @Test
    fun `capacity warning distinguishes full offer from a smaller available quantity`() {
        val full = fixture(capacity = 3)
        val fullHold = pledge(full, PledgeState.AUTHORIZED, 3)
        scriptHoldRead(fullHold)
        val fullHtml = mvc.get("${full.path}/offer").andReturn().response.contentAsString
        assertContains(fullHtml, "This offer is full and is not available for more pledges.")
        assertFalse(fullHtml.contains("Try a smaller quantity."))
        assertFalse(fullHtml.contains("id=\"paypal-button-container\""))

        val limited = fixture(capacity = 3)
        val limitedHold = pledge(limited, PledgeState.AUTHORIZED, 2)
        scriptHoldRead(limitedHold)
        val limitedHtml = mvc.get("${limited.path}/offer") { param("quantity", "2") }
            .andReturn().response.contentAsString
        assertContains(limitedHtml, "There is not enough capacity for this quantity. Try a smaller quantity.")
        assertFalse(limitedHtml.contains("id=\"paypal-button-container\""))
    }

    @Test
    fun `missing PayPal credentials keep the offer readable and checkout unavailable`() {
        val fixture = fixture()
        for (clientId in listOf("", "synthetic-client")) {
            val controller = OfferController(workspaceService, queries,
                PayPalProperties(PayPalProperties.SANDBOX_URL, clientId, ""), evidence, terms)
            val model = ExtendedModelMap()
            assertEquals("fan/offer", controller.offer(fixture.workspace.token, 1, model, MockHttpServletResponse()))
            assertEquals(false, model["paypalReady"])
            assertEquals("", model["paypalClientId"])
            assertEquals("USD 2.00", model["total"])
        }
        assertTrue(gateway.calls.drop(firstCall).isEmpty())
    }

    @Test
    fun `draft and ended offers have no button and terminal offers link to public result`() {
        for (state in listOf(CampaignState.DRAFT, CampaignState.AWAITING_CONFIRMATION, CampaignState.NOT_FUNDED)) {
            val fixture = fixture(state = state, reason = if (state == CampaignState.NOT_FUNDED) CampaignReason.CANCELLED_BY_MAKER else null)
            val html = mvc.get("${fixture.path}/offer").andExpect { status { isOk() } }
                .andReturn().response.contentAsString
            assertFalse(html.contains("id=\"paypal-button-container\""))
            assertFalse(html.contains("paypal.com/sdk/js"))
            if (state == CampaignState.NOT_FUNDED) assertContains(html, "href=\"${fixture.path}/result\"")
        }
    }

    @Test
    fun `pledge page renders every state without payer details and uses shared money wording`() {
        val steps = mapOf(
            PledgeState.CREATED to "AUTHORIZATION",
            PledgeState.ABANDONED to "AUTHORIZATION",
            PledgeState.AUTHORIZED to "HOLD",
            PledgeState.CAPTURED to "CAPTURE",
            PledgeState.CAPTURE_PENDING to "CAPTURE",
            PledgeState.CAPTURE_FAILED to "CAPTURE",
            PledgeState.VOIDED to "RELEASE",
            PledgeState.VOID_FAILED to "RELEASE",
            PledgeState.REFUNDED to "REFUND",
            PledgeState.REFUND_PENDING to "REFUND",
            PledgeState.REFUND_FAILED to "REFUND",
            PledgeState.PARTIALLY_REFUNDED to "REFUND",
        )
        assertEquals(PledgeState.entries.toSet(), steps.keys)
        for (state in PledgeState.entries) {
            val fixture = fixture()
            val pledge = pledge(fixture, state, quantity = 2)
            when (state) {
                PledgeState.AUTHORIZED -> scriptHoldRead(pledge)
                PledgeState.CAPTURE_PENDING -> gateway.readAuthorizationResults.add(received(AuthorizationDetails(
                    AuthorizationId(pledge.authorizationId!!), Money(pledge.amount, pledge.currency), pledge.authorizationExpiresAt,
                ), PaymentStatus.CAPTURED))
                PledgeState.VOID_FAILED -> events.saveAndFlush(Event(fixture.campaign.id, "VOID_RESPONSE", pledge.id,
                    paypalStatus = PaymentStatus.FAILED.name, createdAt = start))
                else -> Unit
            }
            val html = mvc.get("${fixture.path}/pledges/${pledge.id}").andExpect {
                status { isOk() }
                view { name("fan/pledge") }
            }.andReturn().response.contentAsString
            val sentence = messages.getMessage("pledge.$state", null, null, Locale.ENGLISH)
            if (sentence != null) assertContains(html, sentence)
            val map = html.substringAfter("id=\"outcome-map-title\"").substringBefore("</section>")
            assertEquals(1, Regex("aria-current=\"step\"").findAll(map).count())
            val node = map.substringAfter("data-step=\"${steps.getValue(state)}\"").substringBefore("/>")
            assertContains(node, "stroke-width=\"4\"")
            assertEquals(1, Regex("stroke-width=\"4\"").findAll(map).count())
            val expectedStatus = when (state) {
                PledgeState.CREATED -> "Authorization is not confirmed."
                PledgeState.ABANDONED -> "This pledge was not completed"
                PledgeState.AUTHORIZED -> "Hold placed. You have not been charged."
                PledgeState.CAPTURED -> "Paid."
                PledgeState.CAPTURE_PENDING -> "PayPal is still processing your payment."
                PledgeState.CAPTURE_FAILED -> "Your payment could not be completed."
                PledgeState.VOIDED -> "Hold released by PayPal."
                PledgeState.VOID_FAILED -> "The hold release is not confirmed."
                PledgeState.REFUNDED -> "Refunded."
                PledgeState.REFUND_PENDING -> "A refund has been requested. PayPal confirmation is not yet available."
                PledgeState.REFUND_FAILED -> "The refund is not confirmed."
                PledgeState.PARTIALLY_REFUNDED -> "Partial refund observed. Remaining amount is unconfirmed. Needs attention."
            }
            assertContains(map, "Current status: $expectedStatus")
            assertContains(html, "4.00")
            assertContains(html, "USD")
            assertContains(html, "Sandbox demo. No real money moves.")
            assertFalse(html.contains("private-buyer"))
            assertFalse(html.contains("example.test"))
            assertFalse(html.contains("authorization-${pledge.id}"))
            assertFalse(html.contains("??pledge."))
            assertEquals(state == PledgeState.AUTHORIZED, html.contains("Withdraw my pledge"))
            assertEquals(state == PledgeState.AUTHORIZED, html.contains("Hold expires:"))
            assertFalse(html.contains("completed payments are refunded"))
        }
        val reads = gateway.calls.drop(firstCall)
        assertEquals(2, reads.size)
        assertTrue(reads.all { it is PaymentCall.ReadAuthorization })
    }

    @Test
    fun `successful hold leads with batch progress and useful actions before confirmed withdrawal`() {
        val fixture = fixture()
        val pledge = pledge(fixture, PledgeState.AUTHORIZED, quantity = 2)
        scriptHoldRead(pledge)
        val html = mvc.get("${fixture.path}/pledges/${pledge.id}").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val counts = html.indexOf("aria-label=\"Campaign counts\"")
        val campaignLink = html.indexOf("See campaign progress")
        val withdraw = html.indexOf("Withdraw my pledge")
        assertTrue(counts >= 0 && campaignLink > counts && withdraw > campaignLink)
        assertContains(html, "href=\"${fixture.path}/result\"")
        assertContains(html, "aria-label=\"Authorized units toward minimum batch\"")
        assertContains(html, "value=\"2\"")
        val disclosure = html.substringAfter("<details class=\"card pledge-withdraw\"")
            .substringBefore("</details>")
        assertTrue(disclosure.startsWith(">"))
        assertContains(disclosure, "<summary>Withdraw this pledge</summary>")
        assertContains(disclosure, "name=\"confirmed\"")
        assertContains(disclosure, "required")
        assertContains(html, "Trace my pledge")
        assertContains(html, "Latest record entry hash")
        assertContains(html, requireNotNull(recordQuery.findPledgeByToken(fixture.workspace.token, pledge.id)?.lastHash))
        assertFalse(html.contains("Amounts use minor units"))
    }

    @Test
    fun `withdrawal requires confirmation and releases the hold only after a form post`() {
        val fixture = fixture()
        val pledge = pledge(fixture, PledgeState.AUTHORIZED, quantity = 2)
        val path = "${fixture.path}/pledges/${pledge.id}"
        scriptHoldRead(pledge)
        val html = mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(html, "Withdraw my pledge")
        assertContains(html, "name=\"confirmed\"")
        assertContains(html, "Timing depends on PayPal and your bank or card.")
        assertIs<PaymentCall.ReadAuthorization>(gateway.calls.drop(firstCall).single())
        val unconfirmed = mvc.securePost(path) { param("action", "withdraw") }.andExpect {
            status { is3xxRedirection() }; redirectedUrl(path)
        }.andReturn()
        assertContains(unconfirmed.flashMap["error"].toString(), "Confirm")
        assertEquals(PledgeState.AUTHORIZED, pledges.findById(pledge.id).orElseThrow().state)
        assertIs<PaymentCall.ReadAuthorization>(gateway.calls.drop(firstCall).single())
        gateway.voidAuthorizationResults.add(received(VoidDetails(AuthorizationId(pledge.authorizationId!!)), PaymentStatus.VOIDED))
        val withdrawn = mvc.securePost(path) {
            param("action", "withdraw"); param("confirmed", "true")
        }.andExpect { status { is3xxRedirection() }; redirectedUrl(path) }.andReturn()
        assertContains(withdrawn.flashMap["notice"].toString(), "Hold released by PayPal")
        val released = mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(released, "Hold released by PayPal")
        assertFalse(released.contains("Withdraw my pledge"))
        assertEquals(PledgeState.VOIDED, pledges.findById(pledge.id).orElseThrow().state)
        mvc.securePost(path) { param("action", "withdraw"); param("confirmed", "true") }
            .andExpect { status { is3xxRedirection() } }
        assertEquals(1, gateway.calls.drop(firstCall).filterIsInstance<PaymentCall.VoidAuthorization>().size)
    }

    @Test
    fun `closed and settling withdrawals give a plain refusal and foreign pledges stay hidden`() {
        for (state in listOf(CampaignState.AWAITING_CONFIRMATION, CampaignState.SETTLING, CampaignState.FUNDED)) {
            val fixture = fixture(state = state)
            val pledge = pledge(fixture, PledgeState.AUTHORIZED)
            val path = "${fixture.path}/pledges/${pledge.id}"
            val rejected = mvc.securePost(path) { param("action", "withdraw"); param("confirmed", "true") }
                .andExpect { status { is3xxRedirection() }; redirectedUrl(path) }.andReturn()
            assertContains(rejected.flashMap["error"].toString(), "before settlement or closing")
        }
        val fixture = fixture()
        val pledge = pledge(fixture, PledgeState.AUTHORIZED)
        val other = fixture()
        for (path in listOf("${other.path}/pledges/${pledge.id}", "${fixture.path}/pledges/invalid", "/w/missing/pledges/${pledge.id}")) {
            mvc.securePost(path) { param("action", "withdraw"); param("confirmed", "true") }
                .andExpect { status { isNotFound() }; view { name("error/not-found") } }
        }
        assertTrue(gateway.calls.drop(firstCall).isEmpty())
    }

    @Test
    fun `result presents funded not funded and unfinished states without individual pledges`() {
        for (state in listOf(CampaignState.DRAFT, CampaignState.OPEN, CampaignState.FUNDED, CampaignState.NOT_FUNDED)) {
            val fixture = fixture(state = state, reason = if (state == CampaignState.NOT_FUNDED) CampaignReason.CANCELLED_BY_MAKER else null)
            val pledge = pledge(fixture, if (state == CampaignState.FUNDED) PledgeState.CAPTURED else PledgeState.VOIDED)
            val html = mvc.get("${fixture.path}/result").andExpect {
                status { isOk() }
                view { name("fan/result") }
            }.andReturn().response.contentAsString
            if (state == CampaignState.FUNDED || state == CampaignState.NOT_FUNDED) {
                for (label in listOf("Interested in this offer", "Authorized holds", "Paid")) assertContains(html, label)
                assertContains(html, "pledge-experience is-settled")
                assertContains(html.lowercase(), if (state == CampaignState.FUNDED) "funded" else "not funded")
                if (state == CampaignState.FUNDED) {
                    assertContains(html, "The paid minimum was met at settlement.")
                    assertContains(html, "Current payment status may change.")
                } else {
                    assertContains(html, "Refunds and hold releases are attempted; unresolved payments still need attention.")
                }
                val status = mvc.get("${fixture.path}/pledges/${pledge.id}").andReturn().response.contentAsString
                assertContains(status, "href=\"${fixture.path}/result\"")
            }
            assertFalse(html.contains(pledge.id.toString()))
            assertFalse(html.contains("example.test"))
            assertFalse(html.contains("private-buyer"))
            assertFalse(html.contains("paypal.com/sdk/js"))
        }
    }

    @Test
    fun `funded result distinguishes settlement success from current payment shortfall`() {
        val fixture = fixture(state = CampaignState.FUNDED)
        val html = mvc.get("${fixture.path}/result").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertContains(html, "The paid minimum was met at settlement.")
        assertContains(html, "Current completed payments are below the minimum and need attention.")
        assertFalse(html.contains("Enough payments completed to release production."))
    }

    private fun fixture(
        capacity: Int = 5,
        state: CampaignState = CampaignState.OPEN,
        reason: CampaignReason? = null,
    ): Fixture {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().replace("-", "").toByteArray())
        val workspace = workspaces.saveAndFlush(Workspace(token, start))
        ownedWorkspaces.add(workspace.id)
        val option = options.saveAndFlush(Option(workspace.id, "Print", "print", emptyMap(), emptyMap(),
            100, "USD", 1, capacity, "Two weeks", "US"))
        val wishId = UUID.randomUUID()
        val output = AnalysisOutput(listOf(AnalyzedWish(wishId, WishKind.REQUEST, emptyList())), emptyList(),
            Proposal(option.id, emptyMap(), emptyList()), null, emptyList(), emptyList(), emptyList(), listOf(option.id))
        val analysis = analyses.saveAndFlush(AnalysisRun(workspace.id, "synthetic", "hash", mapper.writeValueAsString(output), createdAt = start))
        val offer = offers.saveAndFlush(Offer(workspace.id, option.id, analysis.id, "Moon print", "A5 archival print",
            emptyMap(), 200, "USD", "Shipping included", "US", 1, capacity, minOf(3, capacity), listOf(wishId)))
        val campaign = campaigns.saveAndFlush(Campaign(offer.id, state, reason = reason,
            openedAt = if (state == CampaignState.DRAFT) null else start,
            closesAt = if (state == CampaignState.DRAFT) null else start.plusSeconds(900),
            confirmDeadline = if (state == CampaignState.DRAFT) null else start.plusSeconds(44_100)))
        if (state != CampaignState.DRAFT) persistence.checkpoint { records.recordOffer(offer) }
        return Fixture(workspace, campaign)
    }

    private fun pledge(fixture: Fixture, state: PledgeState, quantity: Int = 1): Pledge {
        val id = UUID.randomUUID()
        return pledges.saveAndFlush(Pledge(fixture.campaign.id, quantity, quantity * 200L, "USD", state,
            paypalOrderId = "order-$id", authorizationId = if (state == PledgeState.CREATED) null else "authorization-$id",
            authorizationExpiresAt = start.plusSeconds(86_400), payerEmailMasked = "private-buyer@example.test",
            createdAt = start, id = id)).also(gateway::registerOrder)
    }

    private fun scriptHoldRead(pledge: Pledge) {
        gateway.readAuthorizationResults.add(received(AuthorizationDetails(
            AuthorizationId(pledge.authorizationId!!), Money(pledge.amount, pledge.currency), pledge.authorizationExpiresAt,
        ), PaymentStatus.CREATED))
    }

    private fun scriptAuthorization(pledge: Pledge, amount: Money = Money(pledge.amount, pledge.currency)) {
        gateway.authorizeOrderResults.add(received(AuthorizationDetails(AuthorizationId("authorization-${pledge.id}"),
            amount, start.plusSeconds(86_400), orderId = OrderId(pledge.paypalOrderId!!)), PaymentStatus.CREATED))
    }

    private fun <T> received(value: T, status: PaymentStatus): PaymentResult<T> = PaymentResult.Received(value, status)

    private data class Fixture(val workspace: Workspace, val campaign: Campaign) {
        val path get() = "/w/${workspace.token}"
    }

    @TestConfiguration
    class PaymentConfiguration {
        @Bean
        @Primary
        fun fanPaymentGateway() = InMemoryPaymentGateway()

        @Bean
        @Primary
        fun fanPaymentClock() = FanPaymentClock()
    }
}

class FanPaymentClock : Clock() {
    var current: Instant = Instant.parse("2026-01-01T00:00:00Z")
    override fun instant(): Instant = current
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
}

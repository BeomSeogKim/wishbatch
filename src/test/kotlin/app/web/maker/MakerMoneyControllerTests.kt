package app.web.maker

import app.campaign.model.AnalysisRun
import app.campaign.model.Event
import app.campaign.model.CampaignState
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.NeedAttribute
import app.campaign.model.NeedStrength
import app.campaign.model.Money
import app.campaign.model.PledgeState
import app.campaign.model.ShippingAddress
import app.campaign.model.Option
import app.campaign.model.WishKind
import app.campaign.model.Workspace
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalyzedWish
import app.campaign.port.AuthorizationDetails
import app.campaign.port.AuthorizationId
import app.campaign.port.CaptureDetails
import app.campaign.port.CaptureId
import app.campaign.port.MakerQuestion
import app.campaign.port.OrderDetails
import app.campaign.port.OrderId
import app.campaign.port.PaymentResult
import app.campaign.port.PaymentStatus
import app.campaign.port.Proposal
import app.campaign.port.ProposalEvidence
import app.campaign.port.RefundDetails
import app.campaign.port.RefundId
import app.campaign.port.VoidDetails
import app.campaign.port.WishNeed
import app.campaign.port.WishVerdict
import app.campaign.service.CampaignRef
import app.campaign.service.CampaignService
import app.campaign.service.PledgeRef
import app.campaign.service.ServiceResult
import app.campaign.store.AnalysisRunRepository
import app.campaign.store.CampaignRepository
import app.campaign.store.EventRepository
import app.campaign.store.OfferRepository
import app.campaign.store.OptionRepository
import app.campaign.store.PledgeRepository
import app.campaign.store.WishRepository
import app.campaign.store.WorkspaceRepository
import app.campaign.workspace.WorkspaceService
import app.testing.InMemoryPaymentGateway
import app.testing.PaymentCall
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
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(MakerMoneyTestConfiguration::class)
class MakerMoneyControllerTests @Autowired constructor(
    private val mvc: MockMvc,
    private val workspaces: WorkspaceService,
    private val analyses: AnalysisRunRepository,
    private val offers: OfferRepository,
    private val campaignRows: CampaignRepository,
    private val events: EventRepository,
    private val pledgeRows: PledgeRepository,
    private val wishes: WishRepository,
    private val options: OptionRepository,
    private val workspaceRows: WorkspaceRepository,
    private val campaigns: CampaignService,
    private val mapper: ObjectMapper,
    private val gateway: InMemoryPaymentGateway,
    private val clock: MakerMoneyClock,
) {
    private val owned = mutableListOf<Workspace>()

    @BeforeEach
    fun resetTime() { clock.current = Instant.parse("2026-01-01T00:00:00Z") }

    @AfterEach
    fun cleanFixtures() {
        owned.forEach { workspace ->
            val workspaceOffers = offers.findByWorkspaceId(workspace.id)
            campaignRows.findByOfferIdIn(workspaceOffers.map { it.id }).forEach { campaign ->
                events.deleteAll(events.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id))
                pledgeRows.deleteAll(pledgeRows.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id))
                campaignRows.delete(campaign)
            }
            offers.deleteAll(workspaceOffers)
            analyses.deleteAll(analyses.findByWorkspaceId(workspace.id))
            wishes.deleteAll(wishes.findByWorkspaceId(workspace.id))
            options.deleteAll(options.findByWorkspaceId(workspace.id))
            workspaceRows.deleteById(workspace.id)
        }
    }

    @Test
    fun `timeline details translate states without rewriting currency or protocol terms`() {
        assertEquals("Hold placed → Paid; USD 30.00; HTTP POST /capture; API", MakerDisplay().detail(
            "AUTHORIZED -> CAPTURED; USD 30.00; HTTP POST /capture; API"))
        assertEquals("Settlement in progress → Not funded: Completed payments below minimum", MakerDisplay().detail(
            "SETTLING -> NOT_FUNDED: CAPTURE_SHORTFALL"))
    }

    @Test
    fun `workspace without analysis renders honest empty states and shared shell`() {
        val workspace = workspaces.create().also(owned::add)
        analyses.deleteAll(analyses.findByWorkspaceId(workspace.id))
        for ((route, text) in listOf("offer" to "No proposal is available.",
            "campaign" to "No published campaign yet.", "result" to "No campaign result yet.")) {
            val html = page(workspace.token, route)
            shell(html)
            assertContains(html, text)
            assertFalse(html.contains("class=\"is-complete\""))
        }
    }

    @Test
    fun `offer prefills option suggested variants questions evidence and exact limits`() {
        val fixture = fixture()
        val html = page(fixture.workspace.token, "offer?run=${fixture.run.id}")
        shell(html)
        for (text in listOf(fixture.option.name, "glossy", "What finish can you deliver?", "I can produce and deliver this as specified.",
            "At least 3 units", "From 1 to 10 units", "Default: 3 units", "15 minutes", "2 hours", "24 hours", "48 hours",
            "USD 6.50", "US", "Use a positive price", "readonly", "price-warning")) assertContains(html, text)
        assertContains(html, "run=${fixture.run.id}")
        assertContains(html, "Postcard set\nSize: A6\nDesigns in a set: 5")
        assertFalse(html.contains("designCount"))
        assertFalse(html.contains("postcard-set"))
        assertContains(html, "value=\"glossy\" selected=\"selected\"")
        assertTrue(Regex("id=\"unitPrice\"[^>]*value=\"16.00\"").containsMatchIn(html))
        assertTrue(Regex("id=\"maxQtyPerPledge\"[^>]*value=\"3\"").containsMatchIn(html))
        assertContains(html, "aria-current=\"step\"")
    }

    @Test
    fun `below cost offer price renders a warning before publishing`() {
        val fixture = fixture()
        val normal = page(fixture.workspace.token, "offer?run=${fixture.run.id}")
        assertTrue(Regex("id=\"price-warning\"[^>]*hidden").containsMatchIn(normal))
        assertFalse(normal.contains("A price below cost is allowed."))
        val html = mvc.get("/w/${fixture.workspace.token}/maker/offer?run=${fixture.run.id}") {
            flashAttr("fields", validFields(fixture) + ("unitPrice" to "1.00"))
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        val warning = Regex("<p id=\"price-warning\"[^>]*>").find(html)!!.value
        assertFalse(warning.contains("hidden"))
        assertContains(html, "The unit price is below the option's unit cost.")
    }

    @Test
    fun `invalid numbers limits blank terms checkbox and unresolved questions redirect with inline errors`() {
        val fixture = fixture()
        val cases = listOf(
            "unitPrice" to "0", "unitPrice" to "1.500", "unitPrice" to "9223372036854775807",
            "minBatch" to "2", "minBatch" to "11", "capacity" to "0", "capacity" to "11",
            "maxQtyPerPledge" to "0", "maxQtyPerPledge" to "11", "duration" to "60", "duration" to "9223372036854775807",
            "title" to " ", "specText" to "", "deliveryTerms" to "", "canDeliver" to "false", "question.0" to "",
            "variant.finish" to "unavailable",
        )
        cases.forEach { (field, value) ->
            val response = postOffer(fixture, mapOf(field to value)).andReturn()
            assertTrue(response.flashMap.containsKey("errors"), "Missing error for $field=$value")
            mvc.get(response.response.redirectedUrl!!) {
                response.flashMap.forEach { (key, item) -> flashAttr(key, item) }
            }.andExpect { status { isOk() } }.andReturn().response.contentAsString.let { html ->
                assertContains(html, "role=\"alert\"")
                assertTrue(Regex("id=\"title\"[^>]*value=\"").containsMatchIn(html))
                if (field == "unitPrice") assertContains(html, "value=\"$value\"")
            }
        }
        assertTrue(offers.findByWorkspaceId(fixture.workspace.id).isEmpty())
    }

    @Test
    fun `price endpoint rechecks limits without changing stored evidence or creating an offer`() {
        val fixture = priceFixture()
        val base = "/w/${fixture.workspace.token}/maker/offer/price-check?run=${fixture.run.id}"
        val original = fixture.run.resultJson
        val initialRuns = analyses.findByWorkspaceId(fixture.workspace.id).size
        mvc.get("$base&unitPrice=10.00").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("application/json") }
            jsonPath("$.interest") { value(2) }
            jsonPath("$.interest") { value(2) }
            jsonPath("$.conditional") { value(1) }
            jsonPath("$.excludedByPrice") { value(0) }
        }
        mvc.get("$base&unitPrice=10.01").andExpect {
            status { isOk() }
            jsonPath("$.interest") { value(1) }
            jsonPath("$.interest") { value(1) }
            jsonPath("$.conditional") { value(1) }
            jsonPath("$.excludedWishes[0].wishId") { value(fixture.wishId.toString()) }
            jsonPath("$.excludedWishes[0].limit") { value("USD 10.00") }
            jsonPath("$.excludedWishes[0].quotes[0]") { value("At most USD 10.00") }
            jsonPath("$.excludedWishes[0].sourceUrl") {
                value("/w/${fixture.workspace.token}/maker/proposal?run=${fixture.run.id}#wish-${fixture.wishId}")
            }
        }
        mvc.get("$base&unitPrice=12.01").andExpect {
            status { isOk() }
            jsonPath("$.interest") { value(1) }
            jsonPath("$.excludedByPrice") { value(2) }
        }
        assertTrue(offers.findByWorkspaceId(fixture.workspace.id).isEmpty())
        assertEquals(original, analyses.findById(fixture.run.id).orElseThrow().resultJson)
        assertEquals(initialRuns, analyses.findByWorkspaceId(fixture.workspace.id).size)
    }

    @Test
    fun `price endpoint rejects invalid prices unknown workspaces foreign runs and missing proposals`() {
        val fixture = priceFixture()
        val base = "/w/${fixture.workspace.token}/maker/offer/price-check?run=${fixture.run.id}"
        for (price in listOf("0", "-1", "1.500", "", "9223372036854775808")) {
            mvc.get("$base&unitPrice=$price").andExpect { status { isBadRequest() } }
        }
        val other = fixture()
        mvc.get("/w/missing/maker/offer/price-check?run=${fixture.run.id}&unitPrice=10.00")
            .andExpect { status { isNotFound() } }
        mvc.get("/w/${other.workspace.token}/maker/offer/price-check?run=${fixture.run.id}&unitPrice=10.00")
            .andExpect { status { isNotFound() } }
        val output = mapper.readValue(fixture.run.resultJson, AnalysisOutput::class.java)
        val run = analyses.saveAndFlush(AnalysisRun(fixture.workspace.id, "test-model", "test-hash",
            mapper.writeValueAsString(output.copy(proposal = null, noProposalReason = "No feasible option")),
            createdAt = clock.instant()))
        mvc.get("/w/${fixture.workspace.token}/maker/offer/price-check?run=${run.id}&unitPrice=10.00")
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `form renders price results and refreshes without javascript or publishing`() {
        val fixture = priceFixture()
        assertContains(page(fixture.workspace.token, "offer?run=${fixture.run.id}"),
            "Interested in this offer: 1; 2 excluded by price")
        val response = postOffer(fixture, mapOf("action" to "check-price", "unitPrice" to "10.01",
            "specText" to "Keep these edited terms", "question.0" to "", "canDeliver" to "false")).andReturn()
        assertFalse(response.flashMap.containsKey("errors"))
        val html = mvc.get(response.response.redirectedUrl!!) {
            response.flashMap.forEach { (key, item) -> flashAttr(key, item) }
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(html, "Interested in this offer: 1; 1 excluded by price")
        assertContains(html, "At most USD 10.00")
        assertContains(html, "Keep these edited terms")
        assertContains(html, "value=\"10.01\"")
        assertContains(html, "Check this price")
        assertContains(html, "/js/offer-price.js")
        assertContains(html, "formnovalidate")
        assertTrue(offers.findByWorkspaceId(fixture.workspace.id).isEmpty())
    }

    @Test
    fun `every question needs a recognized resolution before publishing and rejected posts recheck price`() {
        val fixture = priceFixture()
        for (resolution in listOf("", "open", "forged")) {
            val response = postOffer(fixture, mapOf("question.1" to resolution, "unitPrice" to "10.01")).andReturn()
            assertContains(response.flashMap["errors"].toString(), "Resolve this question")
            val check = response.flashMap["priceCheck"] as OfferPriceView
            assertEquals(1, check.interest)
            assertEquals(1, check.excludedByPrice)
            assertTrue(offers.findByWorkspaceId(fixture.workspace.id).isEmpty())
        }
        postOffer(fixture, mapOf("question.0" to "dismissed", "question.1" to "answered"))
            .andExpect { redirectedUrl("/w/${fixture.workspace.token}/maker/campaign") }
        assertEquals(1, offers.findByWorkspaceId(fixture.workspace.id).size)
    }

    @Test
    fun `below cost price publishes and second active campaign is rejected without replacing terms`() {
        val fixture = fixture()
        val published = postOffer(fixture, mapOf("unitPrice" to "1.00")).andReturn()
        assertEquals("/w/${fixture.workspace.token}/maker/campaign", published.response.redirectedUrl)
        assertContains(published.flashMap["notice"].toString(), "below")
        val first = ref(fixture)
        val view = assertIs<ServiceResult.Success<*>>(campaigns.readCampaign(first)).value as app.campaign.service.CampaignView
        assertEquals(100L, view.offer.unitPrice)
        assertEquals(listOf(fixture.wishId), view.offer.supportingWishIds)
        assertEquals(clock.current.plusSeconds(900), view.closesAt)
        assertEquals(clock.current.plusSeconds(900 + 12 * 3600), view.confirmDeadline)
        val rejected = postOffer(fixture).andReturn()
        assertContains(rejected.flashMap["errors"].toString(), "already active")
        assertEquals(first, ref(fixture))
        assertContains(page(fixture.workspace.token, "campaign"), "USD 1.00")
        val home = page(fixture.workspace.token, "")
        assertContains(home, "Open campaign dashboard")
        assertContains(home, "href=\"/w/${fixture.workspace.token}/offer\"")
    }

    @Test
    fun `books checks show recorded comparisons and keep raw evidence collapsed`() {
        val fixture = fixture()
        publish(fixture)
        val raw = """{"pledges":[{"after":{"match":"MATCH"}}],"marker":"raw-evidence"}"""
        events.saveAndFlush(Event(ref(fixture).campaignId, "BOOKS_CHECK", detail = raw, createdAt = clock.instant().plusSeconds(1)))
        for (route in listOf("campaign", "result")) {
            val html = page(fixture.workspace.token, route)
            assertContains(html, "1 pledge · 1 matched")
            assertEquals(2, Regex("<details><summary>Raw record</summary><pre>").findAll(html).count())
            assertEquals(2, Regex("raw-evidence").findAll(html).count())
        }
    }

    @Test
    fun `dashboard separates counts lists masked pledges real deadlines and blocks below threshold`() {
        val fixture = fixture()
        publish(fixture)
        val html = page(fixture.workspace.token, "campaign")
        shell(html)
        listOf("Interested in this offer", "Authorized holds", "Paid", "Capacity left", "Time left", "2026-01-01T00:15:00Z",
            "2026-01-01T12:15:00Z", "Needs attention", "Campaign and payment timeline", "disabled=\"disabled\"",
            "Open fan offer page (new tab)", "Open public result (new tab)").forEach { assertContains(html, it) }
        assertContains(html, "1 Jan 2026, 00:15 UTC")
        assertContains(html, "data-live-field=\"timeLeft\"")
        assertContains(html, "data-live-field=\"deadlineLabel\"")
        assertContains(html, "View campaign counts and recorded history")
        assertFalse(Regex(">\\s*2026-01-01T").containsMatchIn(html))
        assertFalse(html.contains(">DRAFT<"))
        assertFalse(html.contains("Simulate next capture failure"))
        val blocked = action(fixture, "confirm", mapOf("acknowledged" to "true", "pledgeCount" to "0", "units" to "0", "amount" to "0"))
        assertContains(blocked.andReturn().flashMap["error"].toString(), "threshold not met")
        assertEquals(CampaignState.OPEN, campaignRows.findById(ref(fixture).campaignId).orElseThrow().state)
        authorize(fixture, "masked", 1)
        healthyProviderReads(fixture)
        val pledged = page(fixture.workspace.token, "campaign")
        assertContains(pledged, "m***@example.test")
        assertFalse(pledged.contains("maker-fan@example.test"))
        assertContains(pledged, "Hold placed. You have not been charged.")
        assertContains(pledged, "Pledge 1")
        val pledgeId = pledgeRows.findByCampaignIdOrderByCreatedAtAscIdAsc(ref(fixture).campaignId).single().id
        assertFalse(pledged.contains(pledgeId.toString()))
        assertFalse(pledged.contains("CREATED -&gt; AUTHORIZED"))
        val simulation = action(fixture, "simulate").andReturn()
        assertContains(simulation.flashMap["error"].toString(), "simulation disabled")
    }

    @Test
    fun `confirmation step requires acknowledgement and current totals then renders funded result`() {
        val fixture = fixture()
        publish(fixture)
        authorize(fixture, "funded", 3)
        val review = action(fixture, "review").andReturn().response.redirectedUrl!!
        assertEquals("/w/${fixture.workspace.token}/maker/campaign?review=true", review)
        healthyProviderReads(fixture)
        val html = page(fixture.workspace.token, "campaign?review=true")
        listOf("Review before confirming production", "authorized pledge</span>", "USD 30.00", "A capture can fail", "refunded", "released").forEach { assertContains(html, it) }
        healthyProviderReads(fixture)
        assertContains(action(fixture, "confirm").andReturn().flashMap["error"].toString(), "Review")
        healthyProviderReads(fixture)
        assertContains(action(fixture, "confirm", confirmation(1, 2, 2000)).andReturn().flashMap["error"].toString(), "Review")
        gateway.captureResults.add(PaymentResult.Received(CaptureDetails(CaptureId("cap-funded"), Money(3000, "USD")), PaymentStatus.COMPLETED))
        healthyProviderReads(fixture)
        val result = action(fixture, "confirm", confirmation(1, 3, 3000)).andReturn()
        assertEquals("/w/${fixture.workspace.token}/maker/result", result.response.redirectedUrl)
        healthyProviderReads(fixture)
        val settled = page(fixture.workspace.token, "result")
        shell(settled)
        listOf("Funded", "The paid minimum was met at settlement.", "Paid.", "USD 30.00", "USD 0.00", "cap-funded",
            "Start a new offer from this proposal").forEach { assertContains(settled, it) }
        healthyProviderReads(fixture)
        assertContains(page(fixture.workspace.token, ""), "View settlement result")
        val callCount = gateway.calls.count { it is PaymentCall.Capture }
        healthyProviderReads(fixture)
        action(fixture, "confirm", confirmation(1, 3, 3000))
        assertEquals(callCount, gateway.calls.count { it is PaymentCall.Capture })
        val previous = ref(fixture)
        clock.current = campaignRows.findById(previous.campaignId).orElseThrow().openedAt!!
        publish(fixture)
        assertTrue(ref(fixture).campaignId != previous.campaignId)
        assertEquals(CampaignState.OPEN, campaignRows.findById(ref(fixture).campaignId).orElseThrow().state)
        assertContains(page(fixture.workspace.token, "campaign"), "Open")
    }

    @Test
    fun `funded production list separates completed payments and keeps buyer details off fan pages`() {
        val fixture = fixture()
        publish(fixture)
        authorize(fixture, "batch-paid", 3)
        authorize(fixture, "batch-failed", 1)
        val rows = pledgeRows.findByCampaignIdOrderByCreatedAtAscIdAsc(ref(fixture).campaignId)
        rows[0].shippingAddress = ShippingAddress("Synthetic Buyer", "42 Sample Lane", "Unit 7", "CA", "Example City", "90001", "US")
        rows[1].shippingAddress = ShippingAddress("Excluded Buyer", "99 Unpaid Road", countryCode = "US")
        pledgeRows.saveAllAndFlush(rows)
        healthyProviderReads(fixture)
        assertFalse(page(fixture.workspace.token, "result").contains("Batch to produce"))
        gateway.captureResults.add(PaymentResult.Received(CaptureDetails(CaptureId("cap-batch"), Money(3000, "USD")), PaymentStatus.COMPLETED))
        gateway.captureResults.add(PaymentResult.Failed(PaymentStatus.DECLINED))
        gateway.voidAuthorizationResults.add(PaymentResult.Received(VoidDetails(AuthorizationId("auth-batch-failed")), PaymentStatus.VOIDED))
        healthyProviderReads(fixture)
        action(fixture, "confirm", confirmation(2, 4, 4000))
        healthyProviderReads(fixture)
        val html = page(fixture.workspace.token, "result")
        val production = html.substringAfter("<h2 id=\"batch-heading\">").substringBefore("<h2 id=\"pledges-heading\">")
        for (text in listOf("Batch to produce", "3 units", "USD 30.00", "Synthetic Buyer", "42 Sample Lane", "Unit 7",
            "Example City", "90001", "Paid.", "m***@example.test")) assertContains(production, text)
        val completed = production.substringBefore("<h3>Payments not completed</h3>")
        val incomplete = production.substringAfter("<h3>Payments not completed</h3>")
        assertFalse(completed.contains("Excluded Buyer"))
        assertContains(incomplete, "Excluded Buyer")
        assertContains(incomplete, "99 Unpaid Road")
        assertContains(incomplete, "Hold released by PayPal.")
        assertFalse(html.contains("maker-fan@example.test"))
        val path = "/w/${fixture.workspace.token}"
        for (route in listOf("/result", "/offer", "/pledges/${rows[0].id}", "/pledges/${rows[1].id}")) {
            healthyProviderReads(fixture)
            val publicHtml = mvc.get(path + route).andExpect { status { isOk() } }.andReturn().response.contentAsString
            for (detail in listOf("Synthetic Buyer", "Excluded Buyer", "42 Sample Lane", "99 Unpaid Road", "Unit 7", "90001",
                "maker-fan@example.test", "m***@example.test", "Batch to produce")) assertFalse(publicHtml.contains(detail))
        }
        assertEquals(PledgeState.CAPTURED, pledgeRows.findById(rows[0].id).orElseThrow().state)
        assertEquals(PledgeState.VOIDED, pledgeRows.findById(rows[1].id).orElseThrow().state)
    }

    @Test
    fun `cancel releases each hold and result reports the reason and pledge outcome`() {
        val fixture = fixture()
        publish(fixture)
        authorize(fixture, "cancel", 2)
        gateway.voidAuthorizationResults.add(PaymentResult.Received(VoidDetails(AuthorizationId("auth-cancel")), PaymentStatus.VOIDED))
        val response = action(fixture, "cancel").andReturn()
        assertEquals("/w/${fixture.workspace.token}/maker/result", response.response.redirectedUrl)
        val html = page(fixture.workspace.token, "result")
        listOf("Not funded", "Cancelled by maker", "Hold released by PayPal", "2 units with released holds",
            "auth-cancel", "USD 0.00").forEach { assertContains(html, it) }
        assertFalse(html.contains("Confirm production"))
        val dashboard = page(fixture.workspace.token, "campaign")
        assertContains(dashboard, "Scheduled close")
        assertContains(dashboard, "Confirmation deadline")
        assertTrue(Regex("<p hidden=\"hidden\">Time left:[\\s\\S]*?in the pledge window[\\s\\S]*?</p>").containsMatchIn(dashboard))
        assertContains(dashboard, "<p hidden=\"hidden\">Time left:")
        assertContains(dashboard, "data-live-field=\"timeLeft\"")
        assertContains(dashboard, "View campaign counts and recorded history")
    }

    @Test
    fun `shortfall refunds completed capture releases failed capture hold and shows exact result totals`() {
        val fixture = fixture()
        publish(fixture)
        authorize(fixture, "paid", 2)
        authorize(fixture, "failed", 1)
        gateway.captureResults.add(PaymentResult.Received(CaptureDetails(CaptureId("cap-shortfall"), Money(2000, "USD")), PaymentStatus.COMPLETED))
        gateway.captureResults.add(PaymentResult.Failed(PaymentStatus.DECLINED))
        gateway.refundCaptureResults.add(PaymentResult.Received(RefundDetails(RefundId("refund-shortfall"), Money(2000, "USD")), PaymentStatus.COMPLETED))
        gateway.voidAuthorizationResults.add(PaymentResult.Received(VoidDetails(AuthorizationId("auth-failed")), PaymentStatus.VOIDED))
        healthyProviderReads(fixture)
        action(fixture, "confirm", confirmation(2, 3, 3000))
        val html = page(fixture.workspace.token, "result")
        listOf("Not funded", "Completed payments below minimum", "Refunded.", "Hold released by PayPal", "USD 20.00", "USD 0.00",
            "refund-shortfall", "cap-shortfall", "2 units with completed refunds", "1 unit with released holds").forEach { assertContains(html, it) }
    }

    @Test
    fun `pending capture stays unpaid and visible for attention then a late payment is refunded`() {
        val fixture = fixture()
        publish(fixture)
        authorize(fixture, "pending", 3)
        val capture = CaptureDetails(CaptureId("cap-pending"), Money(3000, "USD"))
        gateway.captureResults.add(PaymentResult.Received(capture, PaymentStatus.PENDING))
        healthyProviderReads(fixture)
        action(fixture, "confirm", confirmation(1, 3, 3000))
        gateway.readCaptureResults.add(PaymentResult.Received(capture, PaymentStatus.PENDING))
        val pending = page(fixture.workspace.token, "result")
        assertContains(pending, "Not funded")
        assertContains(pending, "Refunds of completed payments and release of remaining holds are requested")
        assertFalse(pending.contains("Completed payments are refunded and remaining holds are released"))
        assertContains(pending, "PayPal is still processing your payment.")
        assertContains(pending, "These payments remain unresolved")
        assertContains(pending, "0 units with completed payments retained")
        gateway.readCaptureResults.add(PaymentResult.Received(capture, PaymentStatus.COMPLETED))
        gateway.refundCaptureResults.add(PaymentResult.Received(
            RefundDetails(RefundId("refund-late"), Money(3000, "USD")), PaymentStatus.PENDING))
        val refunding = page(fixture.workspace.token, "result")
        assertContains(refunding, "A refund has been requested. PayPal confirmation is not yet available.")
        assertContains(refunding, "0 units with completed refunds")
        assertContains(refunding, "refund-late")
    }

    @Test
    fun `reading dashboard lazily closes an overdue campaign and expires missed confirmation`() {
        val below = fixture()
        publish(below)
        clock.current = clock.current.plusSeconds(901)
        val closed = page(below.workspace.token, "campaign")
        assertContains(closed, "Not funded")
        assertContains(closed, "Scheduled close")
        assertContains(closed, "<p hidden=\"hidden\">Time left:")
        assertContains(page(below.workspace.token, "result"), "Minimum not met")
        val ready = fixture()
        publish(ready)
        authorize(ready, "expiry", 3)
        clock.current = clock.current.plusSeconds(901)
        healthyProviderReads(ready)
        assertContains(page(ready.workspace.token, "campaign"), "Awaiting maker confirmation")
        gateway.voidAuthorizationResults.add(PaymentResult.Received(VoidDetails(AuthorizationId("auth-expiry")), PaymentStatus.VOIDED))
        clock.current = clock.current.plusSeconds(12 * 3600)
        healthyProviderReads(ready)
        assertContains(page(ready.workspace.token, "result"), "Confirmation expired")
    }

    @Test
    fun `maker can retry a failed hold release after the provider backoff`() {
        val fixture = fixture()
        publish(fixture)
        authorize(fixture, "retry-release", 2)
        val before = gateway.calls.filterIsInstance<PaymentCall.VoidAuthorization>().size
        gateway.voidAuthorizationResults.add(PaymentResult.Failed(PaymentStatus.FAILED))
        action(fixture, "cancel")
        val unresolved = page(fixture.workspace.token, "result")
        assertContains(unresolved, "Retry unresolved payments")
        assertContains(unresolved, "Hold release could not be confirmed")
        action(fixture, "retry")
        assertEquals(before + 1, gateway.calls.filterIsInstance<PaymentCall.VoidAuthorization>().size)
        assertEquals(PledgeState.VOID_FAILED,
            pledgeRows.findByCampaignIdOrderByCreatedAtAscIdAsc(ref(fixture).campaignId).single().state)

        clock.current = clock.current.plusSeconds(60)
        gateway.voidAuthorizationResults.add(PaymentResult.Received(
            VoidDetails(AuthorizationId("auth-retry-release")), PaymentStatus.VOIDED,
        ))
        val retried = action(fixture, "retry").andReturn()
        assertEquals("/w/${fixture.workspace.token}/maker/result", retried.response.redirectedUrl)
        assertContains(retried.flashMap["notice"].toString(), "Payment recheck requested")
        assertEquals(before + 2, gateway.calls.filterIsInstance<PaymentCall.VoidAuthorization>().size)
        val resolved = page(fixture.workspace.token, "result")
        assertContains(resolved, "Hold released by PayPal")
        assertFalse(resolved.contains("Retry unresolved payments"))
    }

    @Test
    fun `funded partial refund shows shortfall and unconfirmed remainder without refund writes`() {
        val fixture = fixture()
        val before = gateway.calls.size
        publish(fixture)
        authorize(fixture, "partial", 3)
        val capture = CaptureDetails(CaptureId("cap-partial"), Money(3000, "USD"), AuthorizationId("auth-partial"))
        gateway.captureResults.add(PaymentResult.Received(capture, PaymentStatus.COMPLETED))
        healthyProviderReads(fixture)
        action(fixture, "confirm", confirmation(1, 3, 3000))
        gateway.readCaptureResults.add(PaymentResult.Received(capture, PaymentStatus.PARTIALLY_REFUNDED))

        val html = page(fixture.workspace.token, "result")

        assertContains(html, "The paid minimum was met at settlement.")
        assertFalse(html.contains("Production is released"))
        assertContains(html, "Partial refund observed. Remaining amount is unconfirmed. Needs attention.")
        assertContains(html, "Completed payments are now below the minimum after settlement.")
        assertContains(html, "0 units with completed payments retained")
        assertContains(html, "0 units with completed refunds")
        assertContains(html, "Retry unresolved payments")
        gateway.readCaptureResults.add(PaymentResult.Received(capture, PaymentStatus.PARTIALLY_REFUNDED))
        action(fixture, "retry")
        val calls = gateway.calls.drop(before)
        assertTrue(calls.none { it is PaymentCall.RefundCapture || it is PaymentCall.VoidAuthorization })
        assertEquals(1, calls.filterIsInstance<PaymentCall.Capture>().size)
    }

    @Test
    fun `all routes reject unknown workspaces and foreign analysis cannot be used`() {
        val fixture = fixture()
        val other = fixture()
        for (route in listOf("offer", "campaign", "result")) {
            mvc.get("/w/missing/maker/$route").andExpect { status { isNotFound() }; view { name("error/not-found") } }
        }
        mvc.securePost("/w/missing/maker/offer") { params(validFields(fixture)) }.andExpect { status { isNotFound() } }
        mvc.securePost("/w/missing/maker/campaign") { param("action", "cancel") }.andExpect { status { isNotFound() } }
        mvc.get("/w/${other.workspace.token}/maker/offer?run=${fixture.run.id}").andExpect { status { isNotFound() } }
        mvc.securePost("/w/${other.workspace.token}/maker/offer") { params(validFields(fixture)) }.andExpect { status { isNotFound() } }
        publish(fixture)
        val mismatch = action(fixture, "cancel", mapOf("campaignId" to UUID.randomUUID().toString())).andReturn()
        assertContains(mismatch.flashMap["error"].toString(), "changed")
        assertEquals(CampaignState.OPEN, campaignRows.findById(ref(fixture).campaignId).orElseThrow().state)
    }

    @Test
    fun `no proposal and escaped specification remain safe and result stays provisional while open`() {
        val fixture = fixture()
        postOffer(fixture, mapOf("specText" to "<script>unsafe</script>"))
        val html = page(fixture.workspace.token, "campaign")
        assertContains(html, "&lt;script&gt;unsafe&lt;/script&gt;")
        assertFalse(html.contains("<script>unsafe"))
        assertContains(page(fixture.workspace.token, "result"), "Settlement is not final.")
        val output = AnalysisOutput(emptyList(), emptyList(), null, "No feasible batch", emptyList(), emptyList(), emptyList(), listOf(fixture.option.id))
        val run = analyses.saveAndFlush(AnalysisRun(fixture.workspace.id, "test", "test", mapper.writeValueAsString(output), createdAt = clock.current))
        assertContains(page(fixture.workspace.token, "offer?run=${run.id}"), "No proposal is available.")
        val result = postOffer(fixture, mapOf("run" to run.id.toString())).andReturn()
        assertContains(result.flashMap["errors"].toString(), "no available proposal")
    }

    private fun priceFixture(): MoneyFixture {
        val base = fixture()
        val otherWishes = workspaces.listWishes(base.workspace.token).take(3)
        val wishes = listOf(
            workspaces.addWish(base.workspace.token, "Postcards please. At most USD 10.00"),
            workspaces.addWish(base.workspace.token, "Postcards if the designs fit. At most USD 12.00"),
        ) + otherWishes
        val output = AnalysisOutput(
            wishes.mapIndexed { index, wish ->
                val limit = when (index) { 0 -> 1000L; 1 -> 1200L; else -> null }
                AnalyzedWish(wish.id, if (index == 4) WishKind.NOT_A_REQUEST else WishKind.REQUEST,
                    if (limit == null) emptyList() else listOf(WishNeed(NeedStrength.HARD, NeedAttribute.PRICE,
                        "Stated limit", if (index == 0) "At most USD 10.00" else "At most USD 12.00")) +
                        if (index == 1) listOf(WishNeed(NeedStrength.UNKNOWN, NeedAttribute.OTHER, "Designs must fit", "if the designs fit")) else emptyList(),
                    limit?.let { Money(it, "USD") })
            },
            wishes.take(4).mapIndexed { index, wish -> WishVerdict(wish.id, base.option.id,
                when (index) { 1 -> CompatibilityVerdict.CONDITIONAL; 3 -> CompatibilityVerdict.INCOMPATIBLE
                    else -> CompatibilityVerdict.COMPATIBLE }, "Format assessment") },
            Proposal(base.option.id, mapOf("finish" to "glossy"), listOf(ProposalEvidence("Fan evidence", wishes[0].id, wishes[0].text))),
            null, emptyList(), listOf(MakerQuestion("Which finish?", listOf(wishes[0].id)),
                MakerQuestion("Which designs?", listOf(wishes[1].id))), emptyList(), listOf(base.option.id))
        val run = analyses.saveAndFlush(AnalysisRun(base.workspace.id, "test-model", "test-hash",
            mapper.writeValueAsString(output), createdAt = clock.instant()))
        return base.copy(run = run, wishId = wishes[0].id)
    }

    private fun fixture(): MoneyFixture = createMoneyFixture(workspaces, analyses, mapper, clock).also { owned.add(it.workspace) }
    private fun page(token: String, route: String): String = mvc.get("/w/$token/maker${if (route.isEmpty()) "" else "/$route"}")
        .andExpect { status { isOk() } }.andReturn().response.contentAsString
    private fun postOffer(fixture: MoneyFixture, changes: Map<String, String> = emptyMap()) =
        mvc.securePost("/w/${fixture.workspace.token}/maker/offer") { params(validFields(fixture) + changes) }
            .andExpect { status { is3xxRedirection() } }
    private fun publish(fixture: MoneyFixture) {
        postOffer(fixture).andExpect { redirectedUrl("/w/${fixture.workspace.token}/maker/campaign") }
    }
    private fun ref(fixture: MoneyFixture) = MakerCampaignLookup(offers, campaignRows).current(fixture.workspace.id)!!
    private fun action(fixture: MoneyFixture, action: String, changes: Map<String, String> = emptyMap()) =
        mvc.securePost("/w/${fixture.workspace.token}/maker/campaign") {
            params(mapOf("action" to action, "campaignId" to ref(fixture).campaignId.toString()) + changes)
        }.andExpect { status { is3xxRedirection() } }
    private fun authorize(fixture: MoneyFixture, name: String, quantity: Int) {
        authorizeMoneyPledge(campaigns, gateway, ref(fixture), name, quantity, clock)
    }
    private fun healthyProviderReads(fixture: MoneyFixture) {
        val ref = ref(fixture)
        val state = campaignRows.findById(ref.campaignId).orElseThrow().state
        pledgeRows.findByCampaignIdOrderByCreatedAtAscIdAsc(ref.campaignId).forEach { pledge ->
            if (state in setOf(CampaignState.OPEN, CampaignState.AWAITING_CONFIRMATION) && pledge.state == PledgeState.AUTHORIZED) {
                gateway.readAuthorizationResults.add(PaymentResult.Received(AuthorizationDetails(
                    AuthorizationId(pledge.authorizationId!!), Money(pledge.amount, pledge.currency), pledge.authorizationExpiresAt,
                ), PaymentStatus.CREATED))
            }
            if (state == CampaignState.FUNDED && pledge.state == PledgeState.CAPTURED) {
                gateway.readCaptureResults.add(PaymentResult.Received(CaptureDetails(
                    CaptureId(pledge.captureId!!), Money(pledge.amount, pledge.currency), AuthorizationId(pledge.authorizationId!!),
                ), PaymentStatus.COMPLETED))
            }
        }
    }
    private fun confirmation(count: Int, units: Int, amount: Int) = mapOf("acknowledged" to "true", "pledgeCount" to "$count", "units" to "$units", "amount" to "$amount")
    private fun shell(html: String) {
        assertContains(html, "Sandbox demo. No real money moves.")
        assertEquals(1, Regex("<h1\\b").findAll(html).count())
        assertEquals(1, Regex("<main\\b").findAll(html).count())
        assertFalse(html.contains("th:replace"))
    }
}

private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.params(fields: Map<String, String>) {
    fields.forEach { (name, value) -> param(name, value) }
}

internal data class MoneyFixture(val workspace: Workspace, val option: Option, val run: AnalysisRun, val wishId: UUID)

internal fun createMoneyFixture(workspaces: WorkspaceService, analyses: AnalysisRunRepository, mapper: ObjectMapper, clock: Clock): MoneyFixture {
    val workspace = workspaces.create()
    val option = workspaces.listOptions(workspace.token).single { it.format == "postcard-set" }
    val wish = workspaces.listWishes(workspace.token).first()
    val output = AnalysisOutput(listOf(AnalyzedWish(wish.id, WishKind.REQUEST, emptyList())),
        listOf(WishVerdict(wish.id, option.id, CompatibilityVerdict.COMPATIBLE, "The requested format fits.")),
        Proposal(option.id, mapOf("finish" to "glossy"), listOf(ProposalEvidence("Fan evidence", wish.id, wish.text))),
        null, emptyList(), listOf(MakerQuestion("What finish can you deliver?", listOf(wish.id))), emptyList(), listOf(option.id))
    val run = analyses.saveAndFlush(AnalysisRun(workspace.id, "test-model", "test-hash", mapper.writeValueAsString(output), createdAt = clock.instant()))
    return MoneyFixture(workspace, option, run, wish.id)
}

internal fun validFields(fixture: MoneyFixture) = mapOf("run" to fixture.run.id.toString(), "title" to fixture.option.name,
    "specText" to "A6 postcard set with five designs and glossy finish", "unitPrice" to "10.00", "minBatch" to "3",
    "capacity" to "10", "maxQtyPerPledge" to "3", "duration" to "15", "deliveryTerms" to "Dispatch in two weeks to US addresses",
    "variant.finish" to "glossy", "canDeliver" to "true", "question.0" to "answered")

internal fun authorizeMoneyPledge(service: CampaignService, gateway: InMemoryPaymentGateway, ref: CampaignRef, name: String, quantity: Int, clock: MakerMoneyClock) {
    gateway.createOrderResults.add(PaymentResult.Received(OrderDetails(OrderId("order-$name")), PaymentStatus.CREATED))
    val pledge = assertIs<ServiceResult.Success<*>>(service.startPledge(ref, quantity)).value as app.campaign.service.PledgeView
    gateway.authorizeOrderResults.add(PaymentResult.Received(AuthorizationDetails(AuthorizationId("auth-$name"),
        Money(quantity * 1000L, "USD"), clock.instant().plusSeconds(3 * 86400), OrderId("order-$name"), "maker-fan@example.test"), PaymentStatus.CREATED))
    assertIs<ServiceResult.Success<*>>(service.completePledge(PledgeRef(ref, pledge.id)))
    clock.current = clock.current.plusSeconds(1)
}

@TestConfiguration
class MakerMoneyTestConfiguration {
    @Bean @Primary fun moneyGateway() = InMemoryPaymentGateway()
    @Bean @Primary fun moneyClock() = MakerMoneyClock()
}

class MakerMoneyClock : Clock() {
    var current: Instant = Instant.parse("2026-01-01T00:00:00Z")
    override fun instant(): Instant = current
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}

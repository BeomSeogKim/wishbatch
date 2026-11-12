package app.web.fan

import app.campaign.model.Campaign
import app.campaign.model.CampaignState
import app.campaign.model.Money
import app.campaign.model.Offer
import app.campaign.model.Pledge
import app.campaign.model.ShippingAddress
import app.campaign.model.Workspace
import app.campaign.port.OrderDetails
import app.campaign.port.OrderId
import app.campaign.port.PaymentGateway
import app.campaign.port.PaymentResult
import app.campaign.port.PaymentStatus
import app.campaign.port.AuthorizationDetails
import app.campaign.port.AuthorizationId
import app.campaign.port.VoidDetails
import app.campaign.provenance.DecisionRecordQuery
import app.campaign.provenance.DecisionRecordService
import app.campaign.service.CampaignPersistence
import app.campaign.service.CampaignRef
import app.campaign.service.CampaignService
import app.campaign.service.PledgeRef
import app.campaign.service.ServiceResult
import app.campaign.store.CampaignRepository
import app.campaign.store.EventRepository
import app.campaign.store.OfferRepository
import app.campaign.store.PledgeRepository
import app.campaign.store.WorkspaceRepository
import app.campaign.terms.TermsCheckOutcome
import app.campaign.terms.TermsCheckService
import app.campaign.terms.TermsCheckStage
import app.campaign.terms.TermsSealQuery
import app.campaign.terms.TermsSealView
import app.testing.InMemoryPaymentGateway
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(TermsSealRenderTests.PaymentConfiguration::class)
class TermsSealRenderTests @Autowired constructor(
    private val mvc: MockMvc,
    private val persistence: CampaignPersistence,
    private val entities: EntityManager,
    private val workspaces: WorkspaceRepository,
    private val offers: OfferRepository,
    private val campaigns: CampaignRepository,
    private val pledges: PledgeRepository,
    private val events: EventRepository,
    private val records: DecisionRecordService,
    private val query: DecisionRecordQuery,
    private val terms: TermsSealQuery,
    private val checks: TermsCheckService,
    private val engine: SpringTemplateEngine,
    private val mapper: JsonMapper,
    private val gateway: RenderPaymentGateway,
    private val service: CampaignService,
) {
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private val owned = mutableListOf<UUID>()

    @BeforeEach
    fun resetGateway() {
        gateway.orderReads = 0
        gateway.result = PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE)
    }

    @AfterEach
    fun cleanup() {
        owned.forEach { id -> persistence.checkpoint {
            val campaignIds = "select c.id from Campaign c where c.offerId in (select o.id from Offer o where o.workspaceId = :id)"
            listOf(
                "DecisionRecordEntry e where e.pledgeId in (select r.pledgeId from PledgeDecisionRecord r where r.workspaceId = :id)",
                "PledgeDecisionRecord r where r.workspaceId = :id", "DecisionSourceSnapshot s where s.workspaceId = :id",
                "Event e where e.campaignId in ($campaignIds)", "Pledge p where p.campaignId in ($campaignIds)",
                "Campaign c where c.id in ($campaignIds)", "Offer o where o.workspaceId = :id", "Workspace w where w.id = :id",
            ).forEach { entities.createQuery("delete from $it").setParameter("id", id).executeUpdate() }
        } }
    }

    @Test
    fun `fan model and fragment expose the existing seal and publication time without writes`() {
        val fixture = fixture()
        val seal = assertNotNull(records.sealedOffer(fixture.workspace.id, fixture.offer.id))
        val before = evidenceCounts(fixture)
        val response = mvc.get("/w/${fixture.workspace.token}/offer").andExpect { status { isOk() } }.andReturn()
        val view = assertIs<TermsSealView>(response.modelAndView!!.model["termsSeal"])
        assertEquals(seal.termsHash, view.termsHash)
        assertEquals(fixture.campaign.openedAt, view.publishedAt)
        assertEquals("AVAILABLE", view.availability)
        val html = fragment(view)
        assertContains(html, seal.termsHash)
        assertContains(html, fixture.campaign.openedAt.toString())
        assertContains(html, "not a financial guarantee or digital signature")
        assertEquals(before, evidenceCounts(fixture))
        assertEquals(0, gateway.orderReads)
    }

    @Test
    fun `legacy seal time and check absence remain explicit after repeated GETs`() {
        val fixture = fixture(sealed = false, publicationTime = null)
        val before = evidenceCounts(fixture)
        repeat(2) {
            val response = mvc.get("/w/${fixture.workspace.token}/offer").andExpect { status { isOk() } }.andReturn()
            val view = assertIs<TermsSealView>(response.modelAndView!!.model["termsSeal"])
            assertEquals("UNAVAILABLE", view.availability)
            assertNull(view.termsHash)
            assertNull(view.publishedAt)
            val fragment = fragment(view)
            assertContains(fragment, "Terms hash unavailable")
            assertContains(fragment, "Unavailable")
            val html = mvc.get(recordPath(fixture)).andExpect { status { isOk() } }.andReturn().response.contentAsString
            assertContains(html, "Unavailable: no terms checks were recorded.")
            val json = mapper.readTree(mvc.get("${recordPath(fixture)}.json")
                .andExpect { status { isOk() } }.andReturn().response.contentAsString)
            assertEquals("UNAVAILABLE", json["pledges"][0]["termsChecksAvailability"].asString())
            assertTrue(json["pledges"][0]["termsChecks"].isEmpty)
        }
        assertEquals(listOf(0L, 0L, 0L), before)
        assertEquals(before, evidenceCounts(fixture))
        assertEquals(0, gateway.orderReads)
    }

    @Test
    fun `HTML and JSON expose actual dated checks beside the chain without buyer details`() {
        val fixture = fixture()
        persistence.checkpoint { records.recordPledge(fixture.pledge) }
        val chain = query.findByToken(fixture.workspace.token)!!.pledges.single().entries
        gateway.result = PaymentResult.Received(OrderDetails(OrderId(fixture.pledge.paypalOrderId!!),
            amount = Money(400, "USD")), PaymentStatus.APPROVED, "synthetic-debug")
        val authorizationId = AuthorizationId("hold-${fixture.pledge.id}")
        gateway.delegate.authorizeOrderResults.add(PaymentResult.Received(AuthorizationDetails(authorizationId,
            Money(400, "USD"), now.plusSeconds(86400), OrderId(fixture.pledge.paypalOrderId!!)), PaymentStatus.CREATED))
        val ref = CampaignRef(fixture.workspace.id, fixture.campaign.id)
        assertIs<ServiceResult.Success<*>>(service.completePledge(PledgeRef(ref, fixture.pledge.id)))
        val beforeCaptureChain = query.findByToken(fixture.workspace.token)!!.pledges.single().entries
        gateway.result = PaymentResult.Received(OrderDetails(OrderId(fixture.pledge.paypalOrderId!!),
            amount = Money(400, "EUR")), PaymentStatus.COMPLETED)
        gateway.delegate.voidAuthorizationResults.add(PaymentResult.Received(VoidDetails(authorizationId), PaymentStatus.VOIDED))
        val settled = assertIs<ServiceResult.Success<*>>(service.confirmProduction(ref))
        assertEquals(CampaignState.NOT_FUNDED, (settled.value as app.campaign.service.CampaignView).state)
        val finalRecord = query.findByToken(fixture.workspace.token)!!.pledges.single()
        assertEquals("VOIDED", finalRecord.entries.last().payload["state"].asString())
        assertEquals("INTACT", finalRecord.integrity)
        assertEquals(chain, beforeCaptureChain.take(chain.size))
        assertEquals(beforeCaptureChain, finalRecord.entries.take(beforeCaptureChain.size))
        assertTrue(gateway.delegate.calls.none { it is app.testing.PaymentCall.Capture })
        val before = evidenceCounts(fixture)
        val jsonText = mvc.get("${recordPath(fixture)}.json").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val json = mapper.readTree(jsonText)
        val rows = json["pledges"][0]["termsChecks"].toList()
        assertEquals("RECORDED", json["pledges"][0]["termsChecksAvailability"].asString())
        assertEquals(setOf("MATCH", "MISMATCH"), rows.map { it["observation"]["outcome"].asString() }.toSet())
        val hash = assertNotNull(records.sealedOffer(fixture.workspace.id, fixture.offer.id)).termsHash
        rows.forEach {
            assertEquals(now.toString(), it["checkedAt"].asString())
            assertEquals(hash, it["observation"]["termsHash"].asString())
            assertEquals(400L, it["observation"]["expectedAmountMinor"].asLong())
        }
        val html = mvc.get(recordPath(fixture)).andExpect { status { isOk() } }.andReturn().response.contentAsString
        listOf("Recorded terms checks", "outside the hash chain", "MATCH", "MISMATCH", now.toString(), hash)
            .forEach { assertContains(html, it) }
        listOf("private-buyer", "private-address", "private-email", "private-error", "shippingAddress", "payerEmail")
            .forEach { assertFalse(jsonText.contains(it)); assertFalse(html.contains(it)) }
        assertEquals(finalRecord.entries, query.findByToken(fixture.workspace.token)!!.pledges.single().entries)
        assertEquals(before, evidenceCounts(fixture))
        assertEquals(2, gateway.orderReads)
    }

    @Test
    fun `changed terms hide the unusable hash and workspace tokens isolate observations`() {
        val fixture = fixture()
        val other = fixture()
        gateway.result = PaymentResult.Received(OrderDetails(OrderId(fixture.pledge.paypalOrderId!!),
            amount = Money(400, "USD")), PaymentStatus.APPROVED)
        checks.check(fixture.pledge, TermsCheckStage.AUTHORIZATION)
        assertNull(terms.findByOffer(other.workspace.id, fixture.offer.id))
        val otherJson = mvc.get("${recordPath(other)}.json").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertFalse(otherJson.contains(fixture.pledge.id.toString()))
        assertFalse(otherJson.contains(fixture.offer.id.toString()))
        assertEquals("UNAVAILABLE", mapper.readTree(otherJson)["pledges"][0]["termsChecksAvailability"].asString())
        persistence.checkpoint {
            entities.createNativeQuery("update offers set delivery_terms = 'Changed delivery' where id = :id")
                .setParameter("id", fixture.offer.id).executeUpdate()
            entities.clear()
        }
        val changed = assertNotNull(terms.findByOffer(fixture.workspace.id, fixture.offer.id))
        assertEquals("MISMATCH", changed.availability)
        assertNull(changed.termsHash)
        assertContains(fragment(changed), "differs from its publication snapshot")
        mvc.get("/w/missing/offer").andExpect { status { isNotFound() } }
        mvc.get("/w/missing/maker/decision-record.json").andExpect { status { isNotFound() } }
    }

    private fun fixture(sealed: Boolean = true, publicationTime: Instant? = now.minusSeconds(60)): Fixture =
        persistence.checkpoint {
            val workspace = workspaces.saveAndFlush(Workspace("${UUID.randomUUID()}1234567", now)).also { owned.add(it.id) }
            val offer = offers.saveAndFlush(Offer(workspace.id, UUID.randomUUID(), UUID.randomUUID(),
                "Postcard batch", "Two postcards", emptyMap(), 200, "USD", "Ships in two weeks", "US", 2, 5))
            val campaign = campaigns.saveAndFlush(Campaign(offer.id, CampaignState.OPEN, openedAt = publicationTime,
                closesAt = now.plusSeconds(900), confirmDeadline = now.plusSeconds(44_100)))
            val pledge = pledges.saveAndFlush(Pledge(campaign.id, 2, 400, "USD", paypalOrderId = "order-${UUID.randomUUID()}",
                payerEmailMasked = "private-email", shippingAddress = ShippingAddress(fullName = "private-buyer",
                    addressLine1 = "private-address"), lastError = "private-error", createdAt = now))
            if (sealed) records.recordOffer(offer)
            Fixture(workspace, offer, campaign, pledge)
        }

    private fun fragment(view: TermsSealView): String =
        engine.process("terms/seal", setOf("seal"), Context(Locale.ENGLISH, mapOf("termsSeal" to view)))

    private fun recordPath(fixture: Fixture) = "/w/${fixture.workspace.token}/maker/decision-record"

    private fun evidenceCounts(fixture: Fixture): List<Long> = persistence.checkpoint {
        listOf(
            entities.createQuery("select count(s) from DecisionSourceSnapshot s where s.workspaceId = :id", Long::class.java)
                .setParameter("id", fixture.workspace.id).singleResult,
            entities.createQuery("select count(r) from PledgeDecisionRecord r where r.workspaceId = :id", Long::class.java)
                .setParameter("id", fixture.workspace.id).singleResult,
            events.findByCampaignIdOrderByCreatedAtAscIdAsc(fixture.campaign.id).count { it.type == "TERMS_CHECK" }.toLong(),
        )
    }

    private data class Fixture(val workspace: Workspace, val offer: Offer, val campaign: Campaign, val pledge: Pledge)

    @TestConfiguration
    class PaymentConfiguration {
        @Bean
        @Primary
        fun termsRenderClock(): Clock = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC)

        @Bean
        @Primary
        fun termsRenderGateway() = RenderPaymentGateway()
    }

    class RenderPaymentGateway(val delegate: InMemoryPaymentGateway = InMemoryPaymentGateway()) : PaymentGateway by delegate {
        var result: PaymentResult<OrderDetails> = PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE)
        var orderReads = 0
        override fun readOrder(orderId: OrderId, idempotencyKey: String): PaymentResult<OrderDetails> {
            orderReads++
            return result
        }
    }
}

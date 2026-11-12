package app.web.livecampaign

import app.campaign.model.Money
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.port.AuthorizationDetails
import app.campaign.port.AuthorizationId
import app.campaign.port.CaptureDetails
import app.campaign.port.CaptureId
import app.campaign.port.PaymentResult
import app.campaign.port.PaymentStatus
import app.campaign.service.CampaignQueries
import app.campaign.service.CampaignRef
import app.campaign.service.CampaignService
import app.campaign.service.ServiceResult
import app.campaign.store.AnalysisRunRepository
import app.campaign.store.CampaignRepository
import app.campaign.store.PledgeRepository
import app.campaign.workspace.WorkspaceService
import app.testing.InMemoryPaymentGateway
import app.testing.PaymentCall
import app.web.maker.MakerMoneyClock
import app.web.maker.MakerMoneyTestConfiguration
import app.web.maker.authorizeMoneyPledge
import app.web.maker.createMoneyFixture
import app.web.maker.validFields
import app.web.security.securePost
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(MakerMoneyTestConfiguration::class)
class LiveCampaignControllerTests @Autowired constructor(
    private val mvc: MockMvc,
    private val workspaces: WorkspaceService,
    private val analyses: AnalysisRunRepository,
    private val rows: CampaignRepository,
    private val pledges: PledgeRepository,
    private val campaigns: CampaignService,
    private val queries: CampaignQueries,
    private val gateway: InMemoryPaymentGateway,
    private val clock: MakerMoneyClock,
    private val mapper: ObjectMapper,
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)
    private val owned = mutableListOf<UUID>()

    @AfterEach
    fun cleanup() {
        transactions.executeWithoutResult {
            owned.forEach { id ->
                val campaignIds = rows.findByWorkspaceId(id).map { it.id }
                campaignIds.forEach { campaignId ->
                    for (entity in listOf("Event", "Pledge")) entityManager.createQuery(
                        "delete from $entity e where e.campaignId = :id").setParameter("id", campaignId).executeUpdate()
                }
                entityManager.createQuery("delete from Campaign c where c.offerId in (select o.id from Offer o where o.workspaceId = :id)")
                    .setParameter("id", id).executeUpdate()
                for (entity in listOf("Offer", "AnalysisRun", "Wish", "Option", "Workspace")) {
                    val field = if (entity == "Workspace") "id" else "workspaceId"
                    entityManager.createQuery("delete from $entity e where e.$field = :id").setParameter("id", id).executeUpdate()
                }
            }
        }
    }

    @Test
    fun `separate counts follow fake authorization and capture with only existing reconciliation operations`() {
        val (token, ref) = published()
        var status = status(token)
        assertEquals("1", status.at("/campaign/counts/interest").asString())
        assertEquals("0", status.at("/campaign/counts/authorizedHolds").asString())
        assertEquals("HOLDS", status.at("/campaign/progress/basis").asString())
        for (path in listOf("/maker/campaign?review=true", "/offer?quantity=2")) {
            val html = mvc.get("/w/$token$path").andExpect { status { isOk() } }.andReturn().response.contentAsString
            for (value in listOf("data-live-campaign", "data-status-url=\"/w/$token/api/campaign-status\"",
                "data-live-field=\"authorizedHolds\"", "/js/live-campaign.js")) assertContains(html, value)
            assertContains(html, if (path.startsWith("/maker/campaign")) "Reload to review current terms and actions" else "Refresh current state")
            assertFalse(html.contains("th:replace"))
        }
        authorizeMoneyPledge(campaigns, gateway, ref, "live", 3, clock)
        val before = gateway.calls.size
        healthyHold()
        status = status(token)
        assertEquals("3", status.at("/campaign/counts/authorizedHolds").asString())
        assertEquals("0", status.at("/campaign/counts/paid").asString())
        assertEquals("3", status.at("/campaign/progress/units").asString())
        val endpointOperations = gateway.calls.drop(before).map { it::class }
        val directBefore = gateway.calls.size
        healthyHold()
        queries.currentCampaign(token)
        assertEquals(endpointOperations, gateway.calls.drop(directBefore).map { it::class })
        assertTrue(endpointOperations.all { it == PaymentCall.ReadAuthorization::class })
        healthyHold()
        gateway.captureResults.add(PaymentResult.Received(CaptureDetails(CaptureId("cap-live"), Money(3000, "USD")), PaymentStatus.COMPLETED))
        campaigns.confirmProduction(ref)
        gateway.readCaptureResults.add(PaymentResult.Received(CaptureDetails(CaptureId("cap-live"), Money(3000, "USD")), PaymentStatus.COMPLETED))
        status = status(token)
        assertEquals("FUNDED", status.at("/campaign/state").asString())
        assertEquals("0", status.at("/campaign/counts/authorizedHolds").asString())
        assertEquals("3", status.at("/campaign/counts/paid").asString())
        assertEquals("PAID", status.at("/campaign/progress/basis").asString())
        assertFalse(status.at("/campaign/pledgesOpen").asBoolean())
    }

    @Test
    fun `unknown token has generic no store 404 and another workspace has explicit no campaign`() {
        val (token, ref) = published()
        mvc.get("/w/unknown/api/campaign-status").andExpect {
            status { isNotFound() }; header { string("Cache-Control", "no-store") }; content { string("") }
        }
        val other = workspaces.create().also { owned.add(it.id) }
        val empty = status(other.token)
        assertEquals("NO_CAMPAIGN", empty["availability"].asString())
        assertTrue(empty["campaign"].isNull)
        assertFalse(empty.toString().contains(ref.campaignId.toString()))
        assertEquals(ref.campaignId.toString(), status(token).at("/campaign/id").asString())
        mvc.get("/w/${other.token}/maker/campaign").andExpect { status { isOk() } }
        mvc.get("/w/${other.token}/offer").andExpect { status { isOk() } }
    }

    @Test
    fun `expired window uses normal deadline reconciliation and real timestamps`() {
        val (token, ref) = published()
        val row = rows.findById(ref.campaignId).orElseThrow()
        clock.current = row.closesAt!!
        val callsBefore = gateway.calls.size
        val status = status(token)
        assertEquals("NOT_FUNDED", status.at("/campaign/state").asString())
        assertFalse(status.at("/campaign/pledgesOpen").asBoolean())
        assertEquals(row.closesAt.toString(), status.at("/campaign/closesAt").asString())
        assertEquals(row.confirmDeadline.toString(), status.at("/campaign/confirmDeadline").asString())
        assertEquals(clock.instant().toString(), status["observedAt"].asString())
        assertEquals(callsBefore, gateway.calls.size)
    }

    @Test
    fun `historical funding with a partial refund remains funded and unresolved without leaking private fields`() {
        val (token, ref) = published()
        authorizeMoneyPledge(campaigns, gateway, ref, "live", 3, clock)
        healthyHold()
        gateway.captureResults.add(PaymentResult.Received(CaptureDetails(CaptureId("cap-live"), Money(3000, "USD")), PaymentStatus.COMPLETED))
        campaigns.confirmProduction(ref)
        gateway.readCaptureResults.add(PaymentResult.Received(CaptureDetails(CaptureId("cap-live"), Money(3000, "USD")), PaymentStatus.PARTIALLY_REFUNDED))
        val status = status(token)
        assertEquals("FUNDED", status.at("/campaign/state").asString())
        assertEquals("0", status.at("/campaign/counts/paid").asString())
        assertTrue(status.at("/campaign/fundedPaidShortfall").asBoolean())
        assertTrue(status.at("/campaign/needsAttention").asBoolean())
        assertEquals(PledgeState.PARTIALLY_REFUNDED, pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(ref.campaignId).single().state)
        val publicFields = setOf("id", "state", "counts", "progress", "capacityLeft", "closesAt", "confirmDeadline",
            "pledgesOpen", "fundedPaidShortfall", "needsAttention", "confirmationVersion")
        assertEquals(publicFields, status["campaign"].propertyNames().toSet())
        for (privateValue in listOf("cap-live", "auth-live", "order-live", "example.test", "\"pledges\"", "timeline", "lastError", "shippingAddress")) {
            assertFalse(status.toString().contains(privateValue))
        }
    }

    @Test
    fun `unavailable interest stays distinct from numeric zero across campaign readouts`() {
        val (token, ref) = published()
        val view = (queries.currentCampaign(token) as ServiceResult.Success).value!!
        assertTrue(view.counts.interestAvailable)
        transactions.executeWithoutResult {
            entityManager.createQuery("delete from Option o where o.id = :id")
                .setParameter("id", view.offer.optionId).executeUpdate()
        }
        val unknown = (queries.currentCampaign(token) as ServiceResult.Success).value!!
        assertFalse(unknown.counts.interestAvailable)
        assertEquals(0, unknown.counts.interest)
        assertEquals(0L, unknown.counts.authorizedHolds)
        assertEquals(0L, unknown.counts.paid)
        val live = status(token)
        assertFalse(live.at("/campaign/counts/interestAvailable").asBoolean())
        for (field in listOf("interest", "conditional", "excludedByPrice", "notServed", "authorizedHolds", "paid")) {
            assertEquals("0", live.at("/campaign/counts/$field").asString())
        }
        val pledge = pledges.saveAndFlush(Pledge(ref.campaignId, 1, view.offer.unitPrice, view.offer.currency,
            createdAt = clock.instant()))
        for (path in listOf("/maker/campaign?review=true", "/offer", "/pledges/${pledge.id}")) {
            val card = interestCard(token, path)
            assertContains(card, "Unavailable")
            assertContains(card, "Conditional interest unavailable.")
            assertFalse(card.contains(">0<"))
        }
        val explanation = mvc.get("/w/$token/how-this-batch-was-chosen").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertContains(explanation, "Unavailable. Interest is not an order or payment.")
        assertContains(explanation, "<dt>Depend on a condition</dt><dd>Unavailable</dd>")
        campaigns.cancel(ref)
        for (path in listOf("/maker/result", "/result")) {
            assertContains(interestCard(token, path), "Unavailable")
        }
    }

    @Test
    fun `available zero remains zero and explanation uses singular and plural wish grammar`() {
        val (token, ref) = published()
        val singular = mvc.get("/w/$token/how-this-batch-was-chosen").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertContains(singular, "1 wish; this is not an order or payment.")
        transactions.executeWithoutResult {
            entityManager.createQuery("update Wish w set w.hidden = true where w.workspaceId = :id")
                .setParameter("id", ref.workspaceId).executeUpdate()
        }
        val view = (queries.currentCampaign(token) as ServiceResult.Success).value!!
        assertTrue(view.counts.interestAvailable)
        assertEquals(0, view.counts.interest)
        val live = status(token)
        assertTrue(live.at("/campaign/counts/interestAvailable").asBoolean())
        assertEquals("0", live.at("/campaign/counts/interest").asString())
        for (path in listOf("/maker/campaign?review=true", "/offer")) {
            val card = interestCard(token, path)
            assertContains(card, ">0</p>")
            assertFalse(card.contains("Unavailable"))
        }
        val explanation = mvc.get("/w/$token/how-this-batch-was-chosen").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertContains(explanation, "0 wishes; these are not orders or payments.")
    }

    private fun interestCard(token: String, path: String): String {
        val html = mvc.get("/w/$token$path").andExpect { status { isOk() } }.andReturn().response.contentAsString
        return Regex("""<div class="count count-interest">([\s\S]*?)</div>""").find(html)?.groupValues?.get(1)
            ?: error("Missing interest card at $path")
    }

    private fun published(): Pair<String, CampaignRef> {
        clock.current = Instant.parse("2026-01-01T00:00:00Z")
        val fixture = createMoneyFixture(workspaces, analyses, mapper, clock)
        owned.add(fixture.workspace.id)
        mvc.securePost("/w/${fixture.workspace.token}/maker/offer") {
            validFields(fixture).forEach { (key, value) -> param(key, value) }
        }.andExpect { status { is3xxRedirection() } }
        return fixture.workspace.token to CampaignRef(fixture.workspace.id, rows.findByWorkspaceId(fixture.workspace.id).single().id)
    }

    private fun healthyHold() {
        gateway.readAuthorizationResults.add(PaymentResult.Received(AuthorizationDetails(AuthorizationId("auth-live"),
            Money(3000, "USD"), clock.instant().plusSeconds(3 * 86400)), PaymentStatus.CREATED))
    }

    private fun status(token: String) = mapper.readTree(mvc.get("/w/$token/api/campaign-status").andExpect {
        status { isOk() }; header { string("Cache-Control", "no-store") }
    }.andReturn().response.contentAsString)
}

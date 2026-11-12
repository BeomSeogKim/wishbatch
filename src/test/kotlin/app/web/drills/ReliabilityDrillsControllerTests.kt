package app.web.drills

import app.campaign.books.BooksCheckService
import app.campaign.model.Campaign
import app.campaign.model.CampaignState
import app.campaign.model.Offer
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.model.Workspace
import app.campaign.port.PaymentGateway
import app.campaign.service.CampaignQueries
import app.campaign.service.CampaignReconciliationTick
import app.campaign.service.CampaignService
import app.campaign.store.CampaignRepository
import app.campaign.store.OfferRepository
import app.campaign.store.PledgeRepository
import app.campaign.store.WorkspaceRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReliabilityDrillsControllerTests @Autowired constructor(
    private val mvc: MockMvc,
    private val workspaces: WorkspaceRepository,
    private val offers: OfferRepository,
    private val campaigns: CampaignRepository,
    private val pledges: PledgeRepository,
    private val jdbc: JdbcTemplate,
) {
    @MockitoBean
    private lateinit var gateway: PaymentGateway

    @MockitoBean
    private lateinit var campaignService: CampaignService

    @MockitoBean
    private lateinit var queries: CampaignQueries

    @MockitoBean
    private lateinit var books: BooksCheckService

    @MockitoBean
    private lateinit var tick: CampaignReconciliationTick

    @Test
    fun `rendering all synthetic drills leaves every database row unchanged and calls no payment collaborators`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val owner = workspaces.saveAndFlush(Workspace("a".repeat(43), now))
        val offer = offers.saveAndFlush(Offer(owner.id, UUID.randomUUID(), UUID.randomUUID(),
            "Stored offer marker", "Stored spec", emptyMap(), 650, "USD", "Delivery", "US", 2, 5))
        val campaign = campaigns.saveAndFlush(Campaign(offer.id, CampaignState.SETTLING))
        pledges.saveAndFlush(Pledge(campaign.id, 1, 650, "USD", PledgeState.CAPTURE_PENDING,
            paypalOrderId = "sim-stored-order-marker", authorizationId = "sim-stored-auth-marker",
            captureId = "sim-stored-capture-marker", createdAt = now))
        val before = databaseRows()
        val path = "/w/${owner.token}/maker/reliability-drills"
        val html = mvc.get(path).andExpect {
            status { isOk() }
            view { name("drills/page") }
        }.andReturn().response.contentAsString
        mvc.get(path).andExpect { status { isOk() } }

        assertEquals(before, databaseRows())
        verifyNoInteractions(gateway, campaignService, queries, books)
        assertEquals(5, Regex("Simulation — no PayPal calls or real campaign changes").findAll(html).count())
        assertEquals(4, Regex("data-drill=\"simulation\"").findAll(html).count())
        for (id in listOf("capture-declined", "capture-timeout", "duplicate-webhook", "pledge-unavailable")) {
            assertContains(html, "id=\"$id\"")
            val result = html.substringAfter("id=\"$id\"").substringBefore("</section>")
            assertContains(result, "Simulation — no PayPal calls or real campaign changes")
        }
        for (label in listOf("All requests", "Authorized holds", "Paid", "Needs attention", "UNKNOWN",
            "Simulated time", "Original idempotency key", "Simulated local and provider books",
            "does not execute or verify production settlement, PayPal recovery, or webhook recovery",
            "does not prove that live books agree", "Sandbox demo. No real money moves.")) {
            assertContains(html, label)
        }
        assertContains(html, "href=\"/w/${owner.token}/maker\"")
        assertContains(html, "Back to maker")
        assertFalse(html.contains("sim-stored-capture-marker"))
        assertFalse(html.contains("Stored offer marker"))
        assertFalse(html.contains("<form"))
    }

    @Test
    fun `an existing workspace session cannot open a missing workspace drill page`() {
        val owner = workspaces.saveAndFlush(Workspace("b".repeat(43), Instant.parse("2026-01-01T00:00:00Z")))
        val session = MockHttpSession()
        mvc.get("/w/${owner.token}/maker/reliability-drills") { this.session = session }
            .andExpect { status { isOk() } }
        val before = databaseRows()
        for (token in listOf("c".repeat(43), "invalid")) {
            mvc.get("/w/$token/maker/reliability-drills") { this.session = session }.andExpect {
                status { isNotFound() }
                view { name("error/not-found") }
            }
        }
        assertEquals(before, databaseRows())
        verifyNoInteractions(gateway, campaignService, queries, books)
    }

    private fun databaseRows(): Map<String, List<List<String?>>> {
        val tables = jdbc.queryForList(
            "select TABLE_NAME from INFORMATION_SCHEMA.TABLES where TABLE_SCHEMA = 'PUBLIC' and TABLE_TYPE = 'BASE TABLE' order by TABLE_NAME",
            String::class.java,
        ).map { requireNotNull(it) }
        return tables.associateWith { table ->
            jdbc.query("select * from \"$table\"") { row, _ ->
                (1..row.metaData.columnCount).map { row.getString(it) }
            }.sortedBy { it.toString() }
        }
    }
}

package app.web.charts

import app.campaign.charts.CampaignCharts
import app.campaign.model.AnalysisRun
import app.campaign.model.Campaign
import app.campaign.model.CampaignState
import app.campaign.model.Event
import app.campaign.model.Offer
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.model.WishKind
import app.campaign.model.Workspace
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalyzedWish
import app.campaign.store.AnalysisRunRepository
import app.campaign.store.CampaignRepository
import app.campaign.store.EventRepository
import app.campaign.store.OfferRepository
import app.campaign.store.PledgeRepository
import app.campaign.store.WorkspaceRepository
import app.testing.InMemoryPaymentGateway
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
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(CampaignChartsWebTests.Configuration::class)
class CampaignChartsWebTests @Autowired constructor(
    private val mvc: MockMvc,
    private val workspaces: WorkspaceRepository,
    private val analyses: AnalysisRunRepository,
    private val offers: OfferRepository,
    private val campaigns: CampaignRepository,
    private val pledges: PledgeRepository,
    private val events: EventRepository,
    private val mapper: JsonMapper,
    private val options: app.campaign.store.OptionRepository,
    private val wishes: app.campaign.store.WishRepository,
    private val gateway: InMemoryPaymentGateway,
) {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private fun workspace() = workspaces.save(Workspace(UUID.randomUUID().toString().replace("-", "") + "12345678901", now))

    private fun fixture(state: CampaignState = CampaignState.FUNDED, validAnalysis: Boolean = true): Pair<Workspace, Campaign> {
        val owner = workspace()
        val option = options.save(app.campaign.model.Option(owner.id, "Cards", "cards", emptyMap(), emptyMap(),
            100, "USD", 3, 10, "Two weeks", "US"))
        val wish = wishes.save(app.campaign.model.Wish(owner.id, "Cards please", app.campaign.model.WishSource.ONSITE, createdAt = now)).id
        val output = AnalysisOutput(listOf(AnalyzedWish(wish, WishKind.REQUEST, emptyList()),
            AnalyzedWish(wish, WishKind.REQUEST, emptyList()), AnalyzedWish(UUID.randomUUID(), WishKind.NOT_A_REQUEST, emptyList())),
            listOf(app.campaign.port.WishVerdict(wish, option.id, app.campaign.model.CompatibilityVerdict.COMPATIBLE, "Fits")),
            null, "No proposal", emptyList(), emptyList(), emptyList(), listOf(option.id))
        val run = analyses.save(AnalysisRun(owner.id, "synthetic", "input", if (validAnalysis) mapper.writeValueAsString(output) else "invalid",
            createdAt = now.minusSeconds(1)))
        val offer = offers.save(Offer(owner.id, option.id, run.id, "Private title <script>unsafe</script>", "Spec",
            emptyMap(), 500, "USD", "Delivery", "US", 3, 10))
        val campaign = campaigns.save(Campaign(offer.id, state, openedAt = now, closesAt = now.plusSeconds(60)))
        events.save(Event(campaign.id, "CAMPAIGN_STATE", detail = "DRAFT -> OPEN", createdAt = now))
        return owner to campaign
    }

    @Test
    fun `deterministic persisted sequence renders exact code counts and source table without payment calls or writes`() {
        val (owner, campaign) = fixture()
        val pledge = pledges.save(Pledge(campaign.id, 3, 1500, "USD", PledgeState.CAPTURED, createdAt = now.plusSeconds(1),
            paypalOrderId = "private-order", authorizationId = "private-auth", captureId = "private-capture",
            payerEmailMasked = "private-email"))
        val records = listOf("CREATED", "CREATED -> AUTHORIZED", "AUTHORIZED -> CAPTURE_PENDING", "CAPTURE_PENDING -> CAPTURED")
            .mapIndexed { index, detail -> events.save(Event(campaign.id, if (index == 0) "PLEDGE_CREATED" else "PLEDGE_STATE",
                pledge.id, paypalId = "private-provider", detail = detail, createdAt = now.plusSeconds(index.toLong() + 1))) }
        val beforeEvents = events.count()
        val beforeCalls = gateway.calls.size
        val path = "/w/${owner.token}/maker/campaign/${campaign.id}/charts"
        val result = mvc.get(path).andExpect { status { isOk() } }.andReturn()
        val view = assertIs<CampaignCharts>(result.modelAndView!!.model["charts"])
        assertEquals(1, view.interest)
        assertEquals(0L, view.authorized)
        assertEquals(3L, view.paid)
        assertEquals(Instant.parse("2026-01-01T01:00:00Z"), view.observedAt)
        assertEquals(listOf(null, 0L, 3L, 0L, 0L), view.history.checkpoints.map { it.authorized })
        assertEquals(listOf(null, 0L, 0L, 0L, 3L), view.history.checkpoints.map { it.paid })
        assertEquals(listOf(null, null, null, null, null), view.history.checkpoints.map { it.interest })
        val html = result.response.contentAsString
        for (text in listOf("Current stages", "<table", "Historical offer interest is unavailable", "these counts do not measure conversion",
            "data-paid=\"3\"", "data-interest=\"1\"", "FUNDED is a historical", "different measures")) assertContains(html, text)
        records.forEach { assertContains(html, it.id.toString()); assertContains(html, it.createdAt.toString()) }
        for (privateValue in listOf("private-order", "private-auth", "private-capture", "private-email", "private-provider", "<script>unsafe")) {
            assertFalse(html.contains(privateValue))
        }
        assertEquals(1, Regex("ag-charts-community@14.3.0").findAll(html).count())
        assertTrue(html.indexOf("ag-charts-community@14.3.0") < html.indexOf("/js/campaign-charts.js"))
        mvc.get(path).andExpect { status { isOk() } }
        assertEquals(beforeCalls, gateway.calls.size)
        assertEquals(beforeEvents, events.count())
        assertEquals(PledgeState.CAPTURED, pledges.findById(pledge.id).orElseThrow().state)
        assertEquals(CampaignState.FUNDED, campaigns.findById(campaign.id).orElseThrow().state)
    }

    @Test
    fun `funded shortfall partial refund unknown remainder malformed history and analysis stay explicit`() {
        val (owner, campaign) = fixture(validAnalysis = false)
        val pledge = pledges.save(Pledge(campaign.id, 3, 1500, "USD", PledgeState.PARTIALLY_REFUNDED, createdAt = now))
        events.save(Event(campaign.id, "PLEDGE_STATE", pledge.id, detail = "<script>private-wish-text</script>", createdAt = now.plusSeconds(1)))
        val response = mvc.get("/w/${owner.token}/maker/campaign/${campaign.id}/charts")
            .andExpect { status { isOk() } }.andReturn()
        val view = assertIs<CampaignCharts>(response.modelAndView!!.model["charts"])
        assertEquals(CampaignState.FUNDED, view.state)
        assertEquals(0L, view.paid)
        assertNull(view.interest)
        assertTrue(view.history.checkpoints.all { it.paid == null && it.interest == null })
        val html = response.response.contentAsString
        assertContains(html, "remainder is unknown")
        assertContains(html, "below the minimum")
        assertContains(html, "Missing pledge creation history")
        assertContains(html, "Unavailable")
        assertFalse(html.contains("private-wish-text"))
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `token campaign offer and analysis references are scoped`() {
        val (owner, campaign) = fixture()
        val other = workspace()
        val before = gateway.calls.size
        mvc.get("/w/${other.token}/maker/campaign/${campaign.id}/charts").andExpect { status { isNotFound() } }
        mvc.get("/w/unknown/maker/campaign/${campaign.id}/charts").andExpect { status { isNotFound() } }
        mvc.get("/w/${owner.token}/maker/campaign/${UUID.randomUUID()}/charts").andExpect { status { isNotFound() } }
        val foreignRun = analyses.save(AnalysisRun(other.id, "synthetic", "input", "{}", createdAt = now))
        val offer = offers.save(Offer(owner.id, UUID.randomUUID(), foreignRun.id, "Offer", "Spec", emptyMap(), 1, "USD", "Delivery", "US", 1, 3))
        val invalid = campaigns.save(Campaign(offer.id))
        val result = mvc.get("/w/${owner.token}/maker/campaign/${invalid.id}/charts").andExpect { status { isOk() } }.andReturn()
        assertNull(assertIs<CampaignCharts>(result.modelAndView!!.model["charts"]).interest)
        assertEquals(before, gateway.calls.size)
    }

    @Test
    fun `expired open campaign remains unchanged and missing publication never becomes historical interest`() {
        val (owner, campaign) = fixture(CampaignState.OPEN)
        events.deleteAll(events.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id))
        val pledge = pledges.save(Pledge(campaign.id, 3, 1500, "USD", PledgeState.AUTHORIZED,
            authorizationId = "private-auth", createdAt = now))
        val beforeEvents = events.count()
        val beforeCalls = gateway.calls.size
        val result = mvc.get("/w/${owner.token}/maker/campaign/${campaign.id}/charts")
            .andExpect { status { isOk() } }.andReturn()
        val view = assertIs<CampaignCharts>(result.modelAndView!!.model["charts"])
        assertEquals(1, view.interest)
        assertEquals(3L, view.authorized)
        assertTrue(view.history.checkpoints.isEmpty())
        assertContains(view.interestHistoryMessage, "visibility and reviewed conditions were not recorded")
        assertEquals(CampaignState.OPEN, campaigns.findById(campaign.id).orElseThrow().state)
        assertEquals(PledgeState.AUTHORIZED, pledges.findById(pledge.id).orElseThrow().state)
        assertEquals(beforeEvents, events.count())
        assertEquals(beforeCalls, gateway.calls.size)
    }

    @TestConfiguration
    class Configuration {
        @Bean
        @Primary
        fun chartsGateway() = InMemoryPaymentGateway()

        @Bean
        @Primary
        fun chartsClock(): Clock = Clock.fixed(Instant.parse("2026-01-01T01:00:00Z"), ZoneOffset.UTC)
    }
}

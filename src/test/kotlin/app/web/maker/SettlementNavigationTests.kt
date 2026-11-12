package app.web.maker

import app.campaign.model.Campaign
import app.campaign.model.CampaignState
import app.campaign.model.Event
import app.campaign.model.Offer
import app.campaign.store.AnalysisRunRepository
import app.campaign.store.CampaignRepository
import app.campaign.store.EventRepository
import app.campaign.store.OfferRepository
import app.campaign.workspace.WorkspaceService
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
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(SettlementNavigationTests.Configuration::class)
class SettlementNavigationTests @Autowired constructor(
    private val mvc: MockMvc,
    private val workspaces: WorkspaceService,
    private val analyses: AnalysisRunRepository,
    private val offers: OfferRepository,
    private val campaigns: CampaignRepository,
    private val events: EventRepository,
    private val gateway: InMemoryPaymentGateway,
) {
    @Test
    fun `terminal settlement links directly to scoped evidence without mutating payment state`() {
        for (state in listOf(CampaignState.FUNDED, CampaignState.NOT_FUNDED)) {
            val owner = workspaces.create()
            val run = analyses.findByWorkspaceId(owner.id).single()
            val offer = offers.save(Offer(owner.id, UUID.randomUUID(), run.id, "Synthetic postcard batch",
                "Five matte A6 designs", emptyMap(), 1000, "USD", "Ships in four weeks", "US", 3, 10))
            val campaign = campaigns.save(Campaign(offer.id, state, openedAt = Instant.parse("2026-01-01T00:00:00Z")))
            val html = mvc.get("/w/${owner.token}/maker/result").andExpect { status { isOk() } }
                .andReturn().response.contentAsString
            val navigation = html.substringAfter("aria-label=\"Settlement next steps\"").substringBefore("</nav>")
            val base = "/w/${owner.token}/maker"
            for (path in listOf("books?campaignId=${campaign.id}", "campaigns/${campaign.id}/trail",
                "reliability-drills", "decision-record")) {
                assertContains(navigation, "href=\"$base/$path\"")
            }
            assertEquals(4, Regex("<a\\b").findAll(navigation).count())
            assertFalse(navigation.contains("<form"))
            assertTrue(navigation.indexOf("Check payment books") < navigation.indexOf("Open decision record"))
            assertTrue(navigation.indexOf("Open decision record") < navigation.indexOf("Payment trail"))
            assertTrue(html.indexOf("Settlement next steps") < html.indexOf("aria-label=\"Campaign counts\""))
            val counts = html.substringAfter("aria-label=\"Campaign counts\"").substringBefore("</section>")
            assertFalse(counts.contains("count-authorized"))
            assertFalse(counts.contains("progress-authorized"))
            assertContains(counts, "progress-paid")
            assertEquals(1, Regex("<progress\\b").findAll(counts).count())
            assertTrue(html.indexOf("Settlement next steps") < html.indexOf("id=\"pledges-heading\""))
            assertEquals(state, campaigns.findById(campaign.id).orElseThrow().state)
            if (state == CampaignState.FUNDED) {
                assertContains(html, "The paid minimum was met at settlement.")
                assertContains(html, "Completed payments are now below the minimum after settlement.")
                assertContains(html, "The campaign retains its funded outcome")
            }
        }
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `timeline leads with five recent human updates and preserves complete provider log in disclosure`() {
        val owner = workspaces.create()
        val run = analyses.findByWorkspaceId(owner.id).single()
        val offer = offers.save(Offer(owner.id, UUID.randomUUID(), run.id, "Synthetic postcard batch",
            "Five matte A6 designs", emptyMap(), 1000, "USD", "Ships in four weeks", "US", 3, 10))
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val campaign = campaigns.save(Campaign(offer.id, CampaignState.FUNDED, openedAt = now))
        for (index in 0..7) {
            events.save(Event(campaign.id, "CAMPAIGN_STATE", detail = "Human checkpoint $index",
                createdAt = now.plusSeconds(index.toLong())))
        }
        val providerId = "synthetic-capture-reference"
        val endpoint = "POST /v2/payments/authorizations/synthetic-authorization/capture"
        events.save(Event(campaign.id, "PAYPAL_CAPTURE_RESPONSE", paypalEndpoint = endpoint,
            paypalId = providerId, paypalStatus = "COMPLETED", detail = "Provider response detail",
            createdAt = now.plusSeconds(9)))
        val html = mvc.get("/w/${owner.token}/maker/result").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val recent = html.substringAfter("aria-label=\"Recent campaign updates\"").substringBefore("</ol>")
        assertEquals(5, Regex("<li\\b").findAll(recent).count())
        assertTrue(recent.indexOf("Human checkpoint 7") < recent.indexOf("Human checkpoint 3"))
        assertFalse(recent.contains("Human checkpoint 2"))
        assertFalse(recent.contains(endpoint))
        assertFalse(recent.contains(providerId))
        val raw = html.substringAfter("<summary>Full campaign and payment log</summary>").substringBefore("</details>")
        assertContains(html, "<details>")
        assertEquals(9, Regex("<li\\b").findAll(raw).count())
        assertContains(raw, endpoint)
        assertContains(raw, "title=\"$providerId\"")
        assertContains(raw, "syntheti…")
        assertContains(raw, "Provider response detail")
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `unpublished workspace has no settlement navigation`() {
        val owner = workspaces.create()
        val html = mvc.get("/w/${owner.token}/maker/result").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertFalse(html.contains("aria-label=\"Settlement next steps\""))
    }

    @TestConfiguration
    class Configuration {
        @Bean @Primary fun settlementNavigationGateway() = InMemoryPaymentGateway()
    }
}

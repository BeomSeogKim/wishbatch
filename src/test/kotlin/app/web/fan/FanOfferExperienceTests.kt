package app.web.fan

import app.campaign.model.AnalysisRun
import app.campaign.model.Campaign
import app.campaign.model.CampaignReason
import app.campaign.model.CampaignState
import app.campaign.model.Offer
import app.campaign.model.Option
import app.campaign.model.Workspace
import app.campaign.port.AnalysisOutput
import app.campaign.port.Proposal
import app.campaign.provenance.DecisionRecordService
import app.campaign.service.CampaignPersistence
import app.campaign.store.AnalysisRunRepository
import app.campaign.store.CampaignRepository
import app.campaign.store.OfferRepository
import app.campaign.store.OptionRepository
import app.campaign.store.WorkspaceRepository
import app.testing.InMemoryPaymentGateway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(properties = ["PAYPAL_CLIENT_ID=sb", "PAYPAL_CLIENT_SECRET=synthetic-secret"])
@AutoConfigureMockMvc
@Import(FanOfferExperienceTests.Configuration::class)
class FanOfferExperienceTests @Autowired constructor(
    private val mvc: MockMvc,
    private val workspaces: WorkspaceRepository,
    private val options: OptionRepository,
    private val analyses: AnalysisRunRepository,
    private val offers: OfferRepository,
    private val campaigns: CampaignRepository,
    private val records: DecisionRecordService,
    private val persistence: CampaignPersistence,
    private val messages: MessageSource,
    private val mapper: ObjectMapper,
    private val templates: SpringTemplateEngine,
) {
    private val start = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `commitment keeps server quote six disclosures sealed terms and live hooks`() {
        val path = fixture()
        val html = page(path, 2)
        assertContains(html, "USD 4.00")
        assertContains(html, "Reviewed total")
        assertContains(html, "href=\"#pledge-checkout\"")
        assertContains(html, "id=\"pledge-checkout\"")
        assertContains(html, "data-quantity=\"2\"")
        assertContains(html, "data-live-action=\"pledge\"")
        assertContains(html, "data-live-campaign")
        assertContains(html, "Terms SHA-256:")
        assertContains(html, "1 Jan 2026, 00:15 UTC")
        val hash = assertNotNull(Regex("title=\"([a-f0-9]{64})\"").find(html)).groupValues[1]
        assertContains(html, hash.take(8) + "…")
        assertFalse(html.contains(">$hash</code>"))
        assertContains(html, "A5 archival print")
        assertContains(html, "Shipping included; dispatched in two weeks")
        for (label in listOf("Interested in this offer", "Authorized holds", "Paid")) assertContains(html, label)
        assertContains(html, "more depend on a condition")
        for (field in listOf("interest", "conditional")) assertContains(html, "data-live-field=\"$field\"")
        for (field in listOf("authorizedHolds", "paid")) assertContains(html, "data-offer-progress=\"$field\"")
        for (number in 1..6) {
            val term = messages.getMessage("money.term.$number", null, Locale.ENGLISH)
            assertContains(html, term)
            assertTrue(html.indexOf(term) < html.indexOf("id=\"paypal-button-container\""))
        }
        assertFalse(html.contains("name=\"amount\""))
    }

    @Test
    fun `invalid quantity omits stale total mobile action and checkout`() {
        val html = page(fixture(), 4)
        assertContains(html, "Choose a quantity within this offer")
        assertFalse(html.contains("id=\"pledge-total\""))
        assertFalse(html.contains("class=\"offer-mobile-bar\""))
        assertFalse(html.contains("id=\"pledge-checkout\""))
    }

    @Test
    fun `closed offer keeps exact terms counts and result without pledge actions`() {
        val html = page(fixture(CampaignState.NOT_FUNDED), 1)
        assertContains(html, "No campaign is open for pledges.")
        assertContains(html, "View campaign result")
        assertContains(html, "offer-settled")
        assertFalse(html.contains("data-offer-progress=\"authorizedHolds\""))
        assertContains(html, "A5 archival print")
        assertContains(html, "Authorized holds")
        assertFalse(html.contains("id=\"pledge-checkout\""))
        assertFalse(html.contains("class=\"offer-mobile-bar\""))
        assertFalse(html.contains("id=\"quantity\""))
    }

    @Test
    fun `public evidence keeps exact quote text without a wisher name`() {
        val quote = "A small print for my desk would be lovely."
        val context = Context(Locale.ENGLISH, mapOf("evidence" to OfferEvidenceView(app.campaign.rules.OfferInterest(interestWishIds = listOf(java.util.UUID.randomUUID())),
            listOf(FanWishQuote(quote)))))
        val html = templates.process("fan/offer-evidence", context)
        assertContains(html, quote)
        assertContains(html, "Anonymous fan")
        assertFalse(html.contains("Synthetic named fan"))
    }

    private fun page(path: String, quantity: Int): String = mvc.get("$path/offer?quantity=$quantity")
        .andExpect { status { isOk() } }.andReturn().response.contentAsString

    private fun fixture(state: CampaignState = CampaignState.OPEN): String {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().replace("-", "").toByteArray())
        val workspace = workspaces.saveAndFlush(Workspace(token, start))
        val option = options.saveAndFlush(Option(workspace.id, "Print", "print", emptyMap(), emptyMap(),
            100, "USD", 3, 10, "Two weeks", "US"))
        val output = AnalysisOutput(emptyList(), emptyList(), Proposal(option.id, emptyMap(), emptyList()),
            null, emptyList(), emptyList(), emptyList(), listOf(option.id))
        val analysis = analyses.saveAndFlush(AnalysisRun(workspace.id, "synthetic", "hash",
            mapper.writeValueAsString(output), createdAt = start))
        val offer = offers.saveAndFlush(Offer(workspace.id, option.id, analysis.id, "Moon print", "A5 archival print",
            emptyMap(), 200, "USD", "Shipping included; dispatched in two weeks", "US", 3, 10, 3))
        campaigns.saveAndFlush(Campaign(offer.id, state,
            reason = if (state == CampaignState.NOT_FUNDED) CampaignReason.CANCELLED_BY_MAKER else null,
            openedAt = start, closesAt = start.plusSeconds(900), confirmDeadline = start.plusSeconds(44_100)))
        persistence.checkpoint { records.recordOffer(offer) }
        return "/w/${workspace.token}"
    }

    @TestConfiguration
    class Configuration {
        @Bean
        @Primary
        fun offerExperienceGateway() = InMemoryPaymentGateway()

        @Bean
        @Primary
        fun offerExperienceClock(): Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    }
}

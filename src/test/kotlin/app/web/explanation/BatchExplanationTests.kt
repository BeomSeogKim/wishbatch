package app.web.explanation

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.model.AnalysisRun
import app.campaign.model.Campaign
import app.campaign.model.CampaignState
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Money
import app.campaign.model.Offer
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.port.AuthorizationDetails
import app.campaign.port.AuthorizationId
import app.campaign.port.PaymentResult
import app.campaign.port.PaymentStatus
import app.campaign.port.ProposalEvidence
import app.campaign.rules.optionWishCounts
import app.campaign.workspace.WorkspaceService
import app.testing.InMemoryPaymentGateway
import app.testing.fixedClock
import app.web.security.securePost
import jakarta.persistence.EntityManager
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
import org.springframework.web.util.HtmlUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
@Import(BatchExplanationTests.Fakes::class)
class BatchExplanationTests @Autowired constructor(
    private val workspaces: WorkspaceService,
    private val runs: AnalysisRunService,
    private val entityManager: EntityManager,
    private val mapper: JsonMapper,
    private val mvc: MockMvc,
    private val payments: InMemoryPaymentGateway,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `public explanation counts follow maker reviews after publication without changing terms or the run`() {
        val workspace = workspaces.create()
        val stored = requireNotNull(runs.latest(workspace.token))
        val proposal = requireNotNull(stored.output.proposal)
        val option = proposal.optionId
        val counts = optionWishCounts(stored.output).single { it.optionId == option }
        val evidence = proposal.rationale.first { item ->
            stored.output.verdicts.any {
                it.wishId == item.wishId && it.optionId == option && it.verdict == CompatibilityVerdict.COMPATIBLE
            }
        }
        val wish = evidence.wishId
        val (offer, campaignId) = transactions.execute {
            val offer = Offer(workspace.id, option, stored.run.id, "Reviewed offer", "Printed cards", emptyMap(),
                200, "USD", "Ships later", "US", 2, 10).also(entityManager::persist)
            val campaign = Campaign(offer.id, CampaignState.OPEN, openedAt = now,
                closesAt = now.plusSeconds(900), confirmDeadline = now.plusSeconds(44100)).also(entityManager::persist)
            offer to campaign.id
        }!!
        val originalTerms = mapper.writeValueAsString(offer)
        val path = "/w/${workspace.token}/campaigns/$campaignId/how-this-batch-was-chosen"
        val originalHtml = mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(HtmlUtils.htmlUnescape(originalHtml), evidence.quote)
        for ((verdict, compatible, notServed) in listOf(
            Triple("INCOMPATIBLE", counts.compatible - 1, counts.notServed + 1),
            Triple("COMPATIBLE", counts.compatible, counts.notServed),
        )) {
            mvc.securePost("/w/${workspace.token}/maker/proposal/review") {
                param("run", stored.run.id.toString())
                param("wish", wish.toString())
                param("option", option.toString())
                param("verdict", verdict)
                param("reason", "The maker reviewed the requested format.")
            }.andExpect { status { is3xxRedirection() } }
            val html = mvc.get(path)
                .andExpect { status { isOk() } }.andReturn().response.contentAsString
            if (verdict == "INCOMPATIBLE") {
                assertFalse(HtmlUtils.htmlUnescape(html).contains(evidence.quote))
            } else {
                assertContains(HtmlUtils.htmlUnescape(html), evidence.quote)
            }
            assertContains(html, "<dt>Compatible</dt><dd>$compatible</dd>")
            assertContains(html, "<dt>Depends</dt><dd>${counts.depends}</dd>")
            assertContains(html, "<dt>Not served</dt><dd>$notServed</dd>")
            assertContains(html, "This stored synthetic reference illustrates the validated analysis format")
            assertEquals(compatible, optionWishCounts(runs.find(workspace.token, stored.run.id)!!.output)
                .single { it.optionId == option }.compatible)
        }
        transactions.executeWithoutResult {
            assertEquals(stored.run.resultJson, entityManager.find(AnalysisRun::class.java, stored.run.id).resultJson)
            assertEquals(originalTerms, mapper.writeValueAsString(entityManager.find(Offer::class.java, offer.id)))
            assertEquals(CampaignState.OPEN, entityManager.find(Campaign::class.java, campaignId).state)
        }
    }

    @Test
    fun `unknown token and workspace without published campaign return not found`() {
        mvc.get("/w/unknown/how-this-batch-was-chosen").andExpect { status { isNotFound() } }
        val workspace = workspaces.create()
        mvc.get("/w/${workspace.token}/how-this-batch-was-chosen").andExpect { status { isNotFound() } }
    }

    @Test
    fun `page shows attached analysis and counts without private or unvalidated evidence`() {
        val workspace = workspaces.create()
        val stored = runs.latest(workspace.token)!!
        val proposal = requireNotNull(stored.output.proposal)
        val option = proposal.optionId
        val privateWish = workspaces.addWish(workspace.token, "Contact private@example.com for my order", "Private Name")
        val seedWish = workspaces.listWishes(workspace.token).first { wish ->
            proposal.rationale.any { it.wishId == wish.id }
        }
        val validQuote = proposal.rationale.first { it.wishId == seedWish.id }.quote
        val output = stored.output.copy(proposal = proposal.copy(rationale = listOf(
            ProposalEvidence("", privateWish.id, privateWish.text),
            ProposalEvidence("", seedWish.id, "invented words"),
            ProposalEvidence("", seedWish.id, validQuote),
        )))
        val authorizationId = AuthorizationId("explanation-authorization-${workspace.id}")
        transactions.executeWithoutResult {
            val run = AnalysisRun(workspace.id, "synthetic", "hash", mapper.writeValueAsString(output), createdAt = now)
            entityManager.persist(run)
            val offer = Offer(workspace.id, option, run.id, "Example offer", "Printed cards", emptyMap(),
                200, "USD", "Ships later", "US", 2, 10)
            entityManager.persist(offer)
            val campaign = Campaign(offer.id, CampaignState.OPEN, openedAt = now,
                closesAt = now.plusSeconds(900), confirmDeadline = now.plusSeconds(44100))
            entityManager.persist(campaign)
            entityManager.persist(Pledge(campaign.id, 2, 400, "USD", PledgeState.AUTHORIZED,
                authorizationId = authorizationId.value, payerEmailMasked = "buyer-secret@example.com", createdAt = now))
            entityManager.persist(Pledge(campaign.id, 1, 200, "USD", PledgeState.CAPTURED,
                payerEmailMasked = "other-buyer@example.com", createdAt = now))
        }
        val authorization = PaymentResult.Received(AuthorizationDetails(
            authorizationId, Money(400, "USD"), now.plusSeconds(86_400),
        ), PaymentStatus.CREATED)
        payments.readAuthorizationResults.add(authorization)
        val html = mvc.get("/w/${workspace.token}/how-this-batch-was-chosen")
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(HtmlUtils.htmlUnescape(html), validQuote)
        assertContains(html, "AI grouped wishes against the maker's feasible options")
        assertFalse(html.contains("This stored synthetic reference illustrates"))
        assertContains(html, "Production and delivery are the maker's responsibility.")
        listOf("How this batch was chosen", "Example offer", "Printed cards", "<dt>Compatible</dt><dd>16</dd>",
            "<dt>Depends</dt><dd>10</dd>", "<dt>Not served</dt><dd>9</dd>", "Authorized holds", ">2</span>",
            "Paid", ">1</span>", "Compatible", "Not served").forEach { assertContains(html, it) }
        listOf("private@example.com", "Private Name", "buyer-secret@example.com", "other-buyer@example.com",
            "invented words").forEach { assertFalse(html.contains(it)) }
        workspaces.setWishHidden(workspace.token, seedWish.id, true)
        payments.readAuthorizationResults.add(authorization)
        val hiddenHtml = mvc.get("/w/${workspace.token}/how-this-batch-was-chosen")
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertFalse(HtmlUtils.htmlUnescape(hiddenHtml).contains(validQuote))
    }

    @Test
    fun `stable route selects historical campaign and rejects missing or foreign campaigns`() {
        val workspace = workspaces.create()
        val other = workspaces.create()
        val stored = runs.latest(workspace.token)!!
        val option = requireNotNull(stored.output.proposal).optionId
        val ids = transactions.execute {
            fun campaign(title: String, state: CampaignState, opened: Instant): UUID {
                val offer = Offer(workspace.id, option, stored.run.id, title, "Cards", emptyMap(),
                    200, "USD", "Ships later", "US", 2, 10)
                entityManager.persist(offer)
                return Campaign(offer.id, state, openedAt = opened, closesAt = now.plusSeconds(900),
                    confirmDeadline = now.plusSeconds(44100)).also(entityManager::persist).id
            }
            listOf(campaign("Historical offer", CampaignState.NOT_FUNDED, now.minusSeconds(10)),
                campaign("Current offer", CampaignState.OPEN, now))
        }!!
        val path = "/w/${workspace.token}/campaigns/${ids[0]}/how-this-batch-was-chosen"
        val html = mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(html, "Historical offer")
        assertFalse(html.contains("Current offer"))
        assertContains(html, "refunds and hold releases are attempted")
        assertContains(html, "may still be pending or need attention")
        val current = mvc.get("/w/${workspace.token}/how-this-batch-was-chosen")
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(current, "Current offer")
        mvc.get("/w/${other.token}/campaigns/${ids[0]}/how-this-batch-was-chosen")
            .andExpect { status { isNotFound() } }
        mvc.get("/w/${workspace.token}/campaigns/${UUID.randomUUID()}/how-this-batch-was-chosen")
            .andExpect { status { isNotFound() } }
        mvc.get("/w/unknown/campaigns/${ids[0]}/how-this-batch-was-chosen")
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `funded result with current paid shortfall reports attention without asserting production`() {
        val workspace = workspaces.create()
        val stored = runs.latest(workspace.token)!!
        val campaignId = transactions.execute {
            val offer = Offer(workspace.id, requireNotNull(stored.output.proposal).optionId, stored.run.id,
                "Earlier funded offer", "Cards", emptyMap(), 200, "USD", "Ships later", "US", 2, 10)
            entityManager.persist(offer)
            val campaign = Campaign(offer.id, CampaignState.FUNDED, openedAt = now,
                closesAt = now.plusSeconds(900), confirmDeadline = now.plusSeconds(44100))
            entityManager.persist(campaign)
            entityManager.persist(Pledge(campaign.id, 2, 400, "USD", PledgeState.REFUNDED, createdAt = now))
            campaign.id
        }!!
        val html = mvc.get("/w/${workspace.token}/campaigns/$campaignId/how-this-batch-was-chosen")
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(html, "previously reached its paid minimum")
        assertContains(html, "Current completed payments are below the minimum")
        assertFalse(html.contains("production to proceed"))
    }

    @Test
    fun `ceramicist and textile pages show their real scenario evidence`() {
        for ((scenario, title, quote) in listOf(
            Triple("ceramicist", "Stoneware cup", "I would buy the cup."),
            Triple("textile", "Canvas pouch", "I would buy the pouch."),
        )) {
            val workspace = workspaces.create(scenario)
            val stored = requireNotNull(runs.latest(workspace.token))
            val proposal = requireNotNull(stored.output.proposal)
            val campaignId = transactions.execute {
                val offer = Offer(workspace.id, proposal.optionId, stored.run.id, title, "Maker-confirmed terms",
                    emptyMap(), 200, "USD", "Ships later", "US", 2, 10)
                entityManager.persist(offer)
                Campaign(offer.id, CampaignState.OPEN, openedAt = now, closesAt = now.plusSeconds(900),
                    confirmDeadline = now.plusSeconds(44100)).also(entityManager::persist).id
            }
            val html = mvc.get("/w/${workspace.token}/campaigns/$campaignId/how-this-batch-was-chosen")
                .andExpect { status { isOk() } }.andReturn().response.contentAsString
            assertContains(html, title)
            assertContains(HtmlUtils.htmlUnescape(html), quote)
            assertContains(html, "Anonymous synthetic wish")
            assertContains(html, "stored analysis of synthetic demo wishes")
            assertContains(html, "This stored synthetic reference illustrates the validated analysis format")
            assertFalse(html.contains("AI grouped wishes against the maker's feasible options"))
            assertContains(html, "Production and delivery are the maker's responsibility.")
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Fakes {
        @Bean @Primary fun explanationPayments() = InMemoryPaymentGateway()
        @Bean @Primary fun explanationClock() = fixedClock(Instant.parse("2026-01-01T00:00:00Z"))
    }
}

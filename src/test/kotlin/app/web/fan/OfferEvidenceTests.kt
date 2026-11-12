package app.web.fan

import app.campaign.model.AnalysisRun
import app.campaign.model.Campaign
import app.campaign.model.CampaignState
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Offer
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.model.WishSource
import app.campaign.model.Workspace
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalyzedWish
import app.campaign.port.Proposal
import app.campaign.port.ProposalEvidence
import app.campaign.port.WishVerdict
import app.campaign.service.OfferEvidenceQuery
import app.testing.InMemoryPaymentGateway
import app.testing.fixedClock
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(OfferEvidenceTests.Fakes::class)
class OfferEvidenceTests @Autowired constructor(
    private val entityManager: EntityManager,
    private val mapper: JsonMapper,
    private val query: OfferEvidenceQuery,
    private val mvc: MockMvc,
    transactionManager: PlatformTransactionManager,
) {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val transactions = TransactionTemplate(transactionManager)
    private val ownedWorkspaces = mutableListOf<UUID>()

    @AfterEach
    fun removeFixtures() {
        transactions.executeWithoutResult {
            ownedWorkspaces.forEach { id ->
                entityManager.createQuery("delete from Campaign c where c.offerId in " +
                    "(select o.id from Offer o where o.workspaceId = :id)")
                    .setParameter("id", id).executeUpdate()
                for (entity in listOf("Offer", "AnalysisRun", "Wish", "Option")) {
                    entityManager.createQuery("delete from $entity e where e.workspaceId = :id")
                        .setParameter("id", id).executeUpdate()
                }
                entityManager.remove(entityManager.find(Workspace::class.java, id))
            }
        }
    }

    @Test
    fun `offer uses its original run and at most three exact quotes without wisher names`() {
        val fixture = fixture()
        transactions.executeWithoutResult {
            entityManager.persist(AnalysisRun(fixture.workspace.id, "synthetic", "newer",
                mapper.writeValueAsString(fixture.output.copy(proposal = null, noProposalReason = "No proposal")),
                createdAt = now.plusSeconds(1)))
        }
        val evidence = assertNotNull(query.findByOffer(fixture.workspace.id, fixture.offer.id))
        assertEquals(5, evidence.counts.interest)
        assertEquals(3, evidence.quotes.size)
        evidence.quotes.forEach { quote ->
            assertTrue(fixture.wishes.single { it.id == quote.wishId }.text.contains(quote.quote))
        }
        val html = render(fixture)
        for (text in listOf("Why this offer", "Interested in this offer", "Visible requests compatible with this offer and price. These are not orders.",
            "AI grouped the wishes; the maker chose and confirmed this offer.", "Anonymous fan")) {
            assertContains(html, text)
        }
        assertContains(html, "data-live-field=\"interest\">5</p>")
        assertEquals(3, Regex("<figure class=\"evidence\">").findAll(html).count())
        evidence.quotes.forEach { assertContains(html, it.quote) }
        fixture.wishes.indices.forEach {
            assertFalse(html.contains("Fan <$it>"))
            assertFalse(html.contains("Fan &lt;$it&gt;"))
        }
    }

    @Test
    fun `the longest exact quote for each wish wins over a shorter or invented quote`() {
        val fixture = fixture { output ->
            output.copy(proposal = output.proposal!!.copy(rationale = output.proposal.rationale.flatMap { evidence ->
                listOf(evidence, evidence.copy(quote = "Please make ${evidence.quote} for me."),
                    evidence.copy(quote = "A much longer invented sentence that never appeared in the wish."))
            }))
        }
        val evidence = assertNotNull(query.findByOffer(fixture.workspace.id, fixture.offer.id))
        assertEquals(fixture.wishes.take(3).map { it.text }, evidence.quotes.map { it.quote })
    }

    @Test
    fun `hidden wishes are never quoted even when hidden after the run`() {
        val fixture = fixture()
        hide(fixture.wishes.take(2))
        val evidence = assertNotNull(query.findByOffer(fixture.workspace.id, fixture.offer.id))
        assertEquals(3, evidence.counts.interest)
        assertEquals(fixture.wishes.drop(2).map { it.id }, evidence.quotes.map { it.wishId })
        val html = render(fixture)
        fixture.wishes.take(2).forEach {
            assertFalse(html.contains("exact request ${fixture.wishes.indexOf(it)}"))
        }
        hide(fixture.wishes)
        assertNull(query.findByOffer(fixture.workspace.id, fixture.offer.id))
        assertFalse(render(fixture).contains("Why this offer"))
    }

    @Test
    fun `unvalidated text and incompatible conditional or non request evidence are not shown`() {
        val fixture = fixture { output ->
            output.copy(
                wishes = output.wishes.mapIndexed { index, wish ->
                    if (index == 4) wish.copy(kind = WishKind.NOT_A_REQUEST) else wish
                },
                verdicts = output.verdicts.mapIndexed { index, verdict ->
                    when (index) {
                        2 -> verdict.copy(verdict = CompatibilityVerdict.CONDITIONAL)
                        3 -> verdict.copy(verdict = CompatibilityVerdict.INCOMPATIBLE)
                        else -> verdict
                    }
                },
                proposal = output.proposal!!.copy(rationale = output.proposal.rationale.mapIndexed { index, evidence ->
                    when (index) {
                        0 -> evidence.copy(quote = "invented quote")
                        1 -> evidence.copy(quote = "exact  request 1")
                        else -> evidence
                    }
                }),
            )
        }
        assertNull(query.findByOffer(fixture.workspace.id, fixture.offer.id))
        val html = render(fixture)
        assertFalse(html.contains("Why this offer"))
        assertFalse(html.contains("invented quote"))
    }

    @Test
    fun `section is absent without evidence or an attached run`() {
        val noEvidence = fixture { output -> output.copy(proposal = output.proposal!!.copy(rationale = emptyList())) }
        assertNull(query.findByOffer(noEvidence.workspace.id, noEvidence.offer.id))
        assertFalse(render(noEvidence).contains("Why this offer"))
        val noRun = fixture(attachRun = false)
        assertNull(query.findByOffer(noRun.workspace.id, noRun.offer.id))
        assertFalse(render(noRun).contains("Why this offer"))
    }

    @Test
    fun `evidence query rejects offers and runs from another workspace`() {
        val fixture = fixture()
        val other = fixture()
        assertNull(query.findByOffer(other.workspace.id, fixture.offer.id))
        val foreignRun = fixture(attachRun = false, runId = other.offer.analysisRunId)
        assertNull(query.findByOffer(foreignRun.workspace.id, foreignRun.offer.id))
    }

    private fun render(fixture: Fixture): String = mvc.get("/w/${fixture.workspace.token}/offer")
        .andExpect { status { isOk() } }.andReturn().response.contentAsString

    private fun hide(wishes: List<Wish>) {
        transactions.executeWithoutResult {
            wishes.forEach { entityManager.find(Wish::class.java, it.id).hidden = true }
        }
    }

    private fun fixture(
        attachRun: Boolean = true,
        runId: UUID = UUID.randomUUID(),
        transform: (AnalysisOutput) -> AnalysisOutput = { it },
    ): Fixture = transactions.execute {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(
            UUID.randomUUID().toString().replace("-", "").toByteArray(),
        )
        val workspace = Workspace(token, now).also(entityManager::persist)
        ownedWorkspaces.add(workspace.id)
        val option = Option(workspace.id, "Print", "print", emptyMap(), emptyMap(),
            100, "USD", 1, 10, "Two weeks", "US").also(entityManager::persist)
        val wishes = (0..4).map { index ->
            Wish(workspace.id, "Please make exact request $index for me.", WishSource.ONSITE,
                "Fan <$index>", now).also(entityManager::persist)
        }
        val output = transform(AnalysisOutput(
            wishes.map { AnalyzedWish(it.id, WishKind.REQUEST, emptyList()) },
            wishes.map { WishVerdict(it.id, option.id, CompatibilityVerdict.COMPATIBLE, "Matches") },
            Proposal(option.id, emptyMap(), wishes.mapIndexed { index, wish ->
                ProposalEvidence("Format", wish.id, "exact request $index")
            }),
            null, emptyList(), emptyList(), emptyList(), listOf(option.id),
        ))
        if (attachRun) entityManager.persist(AnalysisRun(workspace.id, "synthetic", "hash",
            mapper.writeValueAsString(output), createdAt = now, id = runId))
        val offer = Offer(workspace.id, option.id, runId, "Print offer", "Archival print", emptyMap(),
            200, "USD", "Shipping included", "US", 1, 10).also(entityManager::persist)
        entityManager.persist(Campaign(offer.id, CampaignState.OPEN, openedAt = now,
            closesAt = now.plusSeconds(900), confirmDeadline = now.plusSeconds(44_100)))
        entityManager.flush()
        Fixture(workspace, offer, wishes, output)
    }!!

    private data class Fixture(
        val workspace: Workspace,
        val offer: Offer,
        val wishes: List<Wish>,
        val output: AnalysisOutput,
    )

    @TestConfiguration(proxyBeanMethods = false)
    class Fakes {
        @Bean
        @Primary
        fun evidencePayments() = InMemoryPaymentGateway()

        @Bean
        @Primary
        fun evidenceClock() = fixedClock(Instant.parse("2026-01-01T00:00:00Z"))
    }
}

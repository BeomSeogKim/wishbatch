package app.campaign.review

import app.campaign.model.AnalysisRun
import app.campaign.model.Campaign
import app.campaign.model.CampaignState
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Money
import app.campaign.model.NeedAttribute
import app.campaign.model.NeedStrength
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
import app.campaign.port.WishNeed
import app.campaign.port.WishVerdict
import app.campaign.rules.analysisInputHash
import app.campaign.service.OfferEvidenceQuery
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WishReviewOfferTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val entityManager: EntityManager,
    @Autowired private val mapper: JsonMapper,
    @Autowired private val evidence: OfferEvidenceQuery,
) {
    @Test
    fun `maker offer form and price check project reviews while retaining fan budget limits`() {
        val fixture = fixture()
        assertMakerCount(fixture, 2)
        assertPriceCount(fixture, 1000, compatible = 2, excluded = 0)

        review(fixture, fixture.wishes[0], CompatibilityVerdict.INCOMPATIBLE)
        assertMakerCount(fixture, 1)
        assertPriceCount(fixture, 1000, compatible = 1, excluded = 0)
        assertPriceCount(fixture, 1001, compatible = 0, excluded = 1)

        review(fixture, fixture.wishes[0], CompatibilityVerdict.COMPATIBLE)
        assertMakerCount(fixture, 2)
        assertPriceCount(fixture, 1000, compatible = 2, excluded = 0)
        assertPriceCount(fixture, 1001, compatible = 1, excluded = 1)
        val limited = fixture.wishes[1]
        review(fixture, limited, CompatibilityVerdict.COMPATIBLE)
        mvc.get("/w/${fixture.workspace.token}/maker/offer/price-check") {
            param("run", fixture.run.id.toString())
            param("unitPrice", "1001")
        }.andExpect {
            status { isOk() }
            jsonPath("$.compatible") { value(1) }
            jsonPath("$.excludedByPrice[0].wishId") { value(limited.id.toString()) }
            jsonPath("$.excludedByPrice[0].quotes[0]") { value("At most USD 10.00") }
        }
        assertEquals(fixture.run.resultJson, entityManager.find(AnalysisRun::class.java, fixture.run.id).resultJson)
        assertEquals(0L, entityManager.createQuery(
            "select count(o) from Offer o where o.workspaceId = :workspaceId", Long::class.java,
        ).setParameter("workspaceId", fixture.workspace.id).singleResult)
    }

    @Test
    fun `reviews after publication update fan evidence without changing published terms or the run`() {
        val fixture = fixture()
        val offer = Offer(
            fixture.workspace.id, fixture.option.id, fixture.run.id, "Matte card offer", "A6 matte cards",
            mapOf("finish" to "matte"), 1000, "USD", "Dispatch in two weeks", "US", 1, 10,
            maxQtyPerPledge = 2, supportingWishIds = fixture.wishes.take(2).map { it.id },
        ).also(entityManager::persist)
        val campaign = Campaign(
            offer.id, CampaignState.OPEN, openedAt = fixture.workspace.createdAt,
            closesAt = fixture.workspace.createdAt.plusSeconds(900),
            confirmDeadline = fixture.workspace.createdAt.plusSeconds(44_100),
        ).also(entityManager::persist)
        entityManager.flush()
        val immutableTerms = mapper.writeValueAsString(offer)
        assertFanEvidence(fixture, offer, listOf(fixture.wishes[0], fixture.wishes[1]))

        review(fixture, fixture.wishes[0], CompatibilityVerdict.INCOMPATIBLE)
        assertFanEvidence(fixture, offer, listOf(fixture.wishes[1]))
        review(fixture, fixture.wishes[2], CompatibilityVerdict.COMPATIBLE)
        assertFanEvidence(fixture, offer, listOf(fixture.wishes[1], fixture.wishes[2]))
        review(fixture, fixture.wishes[0], CompatibilityVerdict.COMPATIBLE)
        assertFanEvidence(fixture, offer, fixture.wishes)

        entityManager.flush()
        entityManager.clear()
        assertEquals(immutableTerms, mapper.writeValueAsString(entityManager.find(Offer::class.java, offer.id)))
        assertEquals(CampaignState.OPEN, entityManager.find(Campaign::class.java, campaign.id).state)
        assertEquals(fixture.run.resultJson, entityManager.find(AnalysisRun::class.java, fixture.run.id).resultJson)
    }

    private fun assertMakerCount(fixture: Fixture, compatible: Int) {
        val html = mvc.get("/w/${fixture.workspace.token}/maker/offer") {
            param("run", fixture.run.id.toString())
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(html, "$compatible wishes fit this price, 0 are excluded by their stated limit")
        assertContains(html, "$compatible Compatible; 0 Depends.")
    }

    private fun assertPriceCount(fixture: Fixture, price: Long, compatible: Int, excluded: Int) {
        mvc.get("/w/${fixture.workspace.token}/maker/offer/price-check") {
            param("run", fixture.run.id.toString())
            param("unitPrice", price.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.fit") { value(compatible) }
            jsonPath("$.compatible") { value(compatible) }
            jsonPath("$.depends") { value(0) }
            jsonPath("$.excludedByPrice.length()") { value(excluded) }
        }
    }

    private fun assertFanEvidence(fixture: Fixture, offer: Offer, included: List<Wish>) {
        val current = assertNotNull(evidence.findByOffer(fixture.workspace.id, offer.id))
        assertEquals(included.size, current.compatibleWishCount)
        assertPriceCount(fixture, 1000, compatible = included.size, excluded = 0)
        assertEquals(included.map { it.id }.toSet(), current.quotes.map { it.wishId }.toSet())
        current.quotes.forEach { quote ->
            assertContains(fixture.wishes.single { it.id == quote.wishId }.text, quote.quote)
        }
    }

    private fun review(fixture: Fixture, wish: Wish, verdict: CompatibilityVerdict) {
        mvc.post("/w/${fixture.workspace.token}/maker/proposal/review") {
            param("run", fixture.run.id.toString())
            param("wish", wish.id.toString())
            param("option", fixture.option.id.toString())
            param("verdict", verdict.name)
            param("reason", "The catalog ${if (verdict == CompatibilityVerdict.COMPATIBLE) "serves" else "does not serve"} this request.")
        }.andExpect { status { is3xxRedirection() } }
        entityManager.flush()
        entityManager.clear()
    }

    private fun fixture(): Fixture {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(
            UUID.randomUUID().toString().replace("-", "").toByteArray(),
        )
        val workspace = Workspace(token, now).also(entityManager::persist)
        val option = Option(
            workspace.id, "Matte cards", "postcard-set", mapOf("size" to "A6"),
            mapOf("finish" to listOf("matte")), 1000, "USD", 1, 10, "Two weeks", "US",
        ).also(entityManager::persist)
        val wishes = listOf(
            "Matte cards for my desk please.",
            "Matte cards please. At most USD 10.00",
            "Large posters would suit my wall.",
        ).mapIndexed { index, text ->
            Wish(workspace.id, text, WishSource.ONSITE, "Fan $index", now).also(entityManager::persist)
        }
        val output = AnalysisOutput(
            wishes.mapIndexed { index, wish ->
                AnalyzedWish(wish.id, WishKind.REQUEST, listOf(WishNeed(
                    NeedStrength.HARD, if (index == 1) NeedAttribute.PRICE else NeedAttribute.FORMAT,
                    if (index == 1) "Stated budget" else "Requested format",
                    if (index == 1) "At most USD 10.00" else wish.text,
                )), if (index == 1) Money(1000, "USD") else null)
            },
            wishes.mapIndexed { index, wish -> WishVerdict(
                wish.id, option.id,
                if (index == 2) CompatibilityVerdict.INCOMPATIBLE else CompatibilityVerdict.COMPATIBLE,
                "Catalog format assessment.",
            ) },
            Proposal(option.id, mapOf("finish" to "matte"), wishes.take(2).map { wish ->
                ProposalEvidence("A fan requested cards.", wish.id, wish.text)
            }),
            null, emptyList(), emptyList(), emptyList(), listOf(option.id),
        )
        val run = AnalysisRun(
            workspace.id, "synthetic-model", analysisInputHash(listOf(option), wishes),
            mapper.writeValueAsString(output), createdAt = now,
        ).also(entityManager::persist)
        entityManager.flush()
        return Fixture(workspace, option, wishes, run)
    }

    private data class Fixture(val workspace: Workspace, val option: Option, val wishes: List<Wish>, val run: AnalysisRun)
}

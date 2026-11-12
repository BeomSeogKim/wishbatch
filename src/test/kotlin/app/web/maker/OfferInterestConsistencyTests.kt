package app.web.maker

import app.campaign.charts.CampaignChartsQuery
import app.campaign.model.AnalysisRun
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Money
import app.campaign.model.NeedAttribute
import app.campaign.model.NeedStrength
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
import app.campaign.review.WishReviewOverride
import app.campaign.rules.OfferInterest
import app.campaign.service.CampaignQueries
import app.campaign.service.CampaignRef
import app.campaign.service.CampaignService
import app.campaign.service.CampaignView
import app.campaign.service.DraftCreated
import app.campaign.service.DraftOffer
import app.campaign.service.ServiceResult
import app.testing.InMemoryPaymentGateway
import app.testing.fixedClock
import app.web.fan.OfferController as FanOfferController
import app.web.fan.OfferEvidenceView
import app.web.fan.ResultController as FanResultController
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
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.ui.ExtendedModelMap
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@SpringBootTest
@AutoConfigureMockMvc
@Import(OfferInterestConsistencyTests.Fakes::class)
class OfferInterestConsistencyTests @Autowired constructor(
    private val entities: EntityManager,
    private val mapper: JsonMapper,
    private val campaigns: CampaignService,
    private val queries: CampaignQueries,
    private val charts: CampaignChartsQuery,
    private val proposal: ProposalController,
    private val offerForm: OfferController,
    private val maker: CampaignController,
    private val fanOffer: FanOfferController,
    private val fanResult: FanResultController,
    private val mvc: MockMvc,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)
    private var workspaceId: UUID? = null
    private var campaignId: UUID? = null

    @AfterEach
    fun removeFixture() {
        transactions.executeWithoutResult {
            campaignId?.let { id ->
                entities.createQuery("delete from Event e where e.campaignId = :id").setParameter("id", id).executeUpdate()
                entities.createQuery("delete from Campaign c where c.id = :id").setParameter("id", id).executeUpdate()
            }
            workspaceId?.let { id ->
                for (type in listOf("DecisionSourceSnapshot", "WishReviewOverride", "Offer", "AnalysisRun", "Wish", "Option")) {
                    entities.createQuery("delete from $type e where e.workspaceId = :id").setParameter("id", id).executeUpdate()
                }
                entities.createQuery("delete from Workspace w where w.id = :id").setParameter("id", id).executeUpdate()
            }
        }
    }

    @Test
    fun `published price and reviewed wish groups agree across every offer and result read model`() {
        val fixture = fixture()
        val draft = assertIs<ServiceResult.Success<DraftCreated>>(campaigns.createDraft(fixture.workspace.id,
            DraftOffer(fixture.option.id, fixture.run.id, "Card offer", "Matte cards", emptyMap(), 1000,
                "Two weeks", 3, 10, Duration.ofMinutes(15), supportingWishIds = listOf(fixture.wishes[0].id))))
        campaignId = draft.value.campaignId
        val ref = CampaignRef(fixture.workspace.id, draft.value.campaignId)
        assertIs<ServiceResult.Success<CampaignView>>(campaigns.publish(ref, Duration.ofMinutes(15)))
        val expected = OfferInterest(
            listOf(0, 1, 7, 8).map { fixture.wishes[it].id }.sortedBy(UUID::toString),
            listOf(fixture.wishes[2].id), listOf(fixture.wishes[6].id), listOf(fixture.wishes[3].id),
        )
        transactions.executeWithoutResult {
            entities.persist(AnalysisRun(fixture.workspace.id, "synthetic", "newer-run",
                mapper.writeValueAsString(fixture.output.copy(verdicts = fixture.output.verdicts.map {
                    it.copy(verdict = CompatibilityVerdict.INCOMPATIBLE)
                })), createdAt = now.plusSeconds(1)))
        }

        val current = assertNotNull(assertIs<ServiceResult.Success<CampaignView?>>(queries.currentCampaign(fixture.workspace.token)).value)
        assertGroups(expected, current.counts)
        assertGroups(expected, assertIs<ServiceResult.Success<CampaignView>>(queries.campaignById(fixture.workspace.token, ref.campaignId)).value.counts)
        val proposalModel = ExtendedModelMap()
        proposal.proposal(fixture.workspace.token, null, proposalModel)
        assertGroups(expected, assertNotNull(proposalModel["proposedCounts"]))
        val formModel = ExtendedModelMap()
        offerForm.offer(fixture.workspace.token, null, formModel)
        assertGroups(expected, assertNotNull(formModel["priceCheck"]))
        assertEquals("10.00", (formModel["fields"] as Map<*, *>)["unitPrice"])
        val priceJson = mvc.get("/w/${fixture.workspace.token}/maker/offer/price-check?run=${fixture.run.id}&unitPrice=10.00")
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertGroups(expected, mapper.readTree(priceJson))
        assertGroups(expected, assertNotNull(charts.read(fixture.workspace.token, ref.campaignId)))
        val fanModel = ExtendedModelMap()
        fanOffer.offer(fixture.workspace.token, 1, fanModel, MockHttpServletResponse())
        assertGroups(expected, assertIs<CampaignView>(fanModel["campaign"]).counts)
        assertGroups(expected, assertIs<OfferEvidenceView>(fanModel["offerEvidence"]).counts)
        val makerModel = ExtendedModelMap()
        maker.campaign(fixture.workspace.token, false, makerModel)
        assertGroups(expected, assertIs<CampaignView>(makerModel["campaign"]).counts)

        assertIs<ServiceResult.Success<CampaignView>>(campaigns.cancel(ref))
        val makerResultModel = ExtendedModelMap()
        maker.result(fixture.workspace.token, makerResultModel)
        assertGroups(expected, assertIs<CampaignView>(makerResultModel["campaign"]).counts)
        val fanResultModel = ExtendedModelMap()
        fanResult.result(fixture.workspace.token, fanResultModel, MockHttpServletResponse())
        assertEquals(true, fanResultModel["resultAvailable"])
        assertGroups(expected, assertIs<CampaignView>(fanResultModel["campaign"]).counts)
    }

    private fun assertGroups(expected: OfferInterest, actual: Any) {
        val expectedJson = mapper.valueToTree<JsonNode>(expected)
        val actualJson = if (actual is JsonNode) actual else mapper.valueToTree<JsonNode>(actual)
        for (name in listOf("interest", "conditional", "excludedByPrice", "notServed", "interestWishIds",
            "conditionalWishIds", "excludedByPriceWishIds", "notServedWishIds")) {
            assertEquals(expectedJson.path(name), actualJson.path(name), name)
        }
    }

    private fun fixture(): Fixture = transactions.execute {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().replace("-", "").toByteArray())
        val workspace = Workspace(token, now).also(entities::persist)
        workspaceId = workspace.id
        val option = Option(workspace.id, "Cards", "cards", emptyMap(), emptyMap(), 650, "USD", 3, 10, "Two weeks", "US")
            .also(entities::persist)
        val texts = listOf("Up to USD 12", "Up to USD 10", "Up to USD 12; matte only", "Different format please",
            "Hidden cards", "Great work", "Up to USD 9.99", "Cards after maker review", "Matte after my answer")
        val wishes = texts.mapIndexed { index, text ->
            Wish(workspace.id, text, WishSource.ONSITE, "Private fan $index", now, hidden = index == 4).also(entities::persist)
        }
        val analyzed = wishes.mapIndexed { index, wish ->
            val limit = when (index) { 0, 2 -> 1200L; 1 -> 1000L; 6 -> 999L; else -> null }
            val needs = buildList {
                if (limit != null) add(WishNeed(NeedStrength.HARD, NeedAttribute.PRICE, "Price limit", wish.text.substringBefore(';')))
                if (index == 2 || index == 8) add(WishNeed(NeedStrength.UNKNOWN, NeedAttribute.FINISH, "Matte", wish.text))
            }
            AnalyzedWish(wish.id, if (index == 5) WishKind.NOT_A_REQUEST else WishKind.REQUEST, needs,
                limit?.let { Money(it, "USD") })
        }
        val output = AnalysisOutput(analyzed, wishes.filterIndexed { index, _ -> index != 5 }.map { wish ->
            val index = wishes.indexOf(wish)
            WishVerdict(wish.id, option.id, when (index) {
                1, 2, 8 -> CompatibilityVerdict.CONDITIONAL
                3, 7 -> CompatibilityVerdict.INCOMPATIBLE
                else -> CompatibilityVerdict.COMPATIBLE
            }, "Recorded assessment")
        }, Proposal(option.id, emptyMap(), listOf(ProposalEvidence("Cards", wishes[0].id, wishes[0].text))),
            null, emptyList(), emptyList(), emptyList(), listOf(option.id))
        val run = AnalysisRun(workspace.id, "synthetic", "offer-interest-fixture", mapper.writeValueAsString(output), createdAt = now)
            .also(entities::persist)
        for ((index, source) in listOf(7 to "MAKER", 8 to "FAN_ANSWER")) {
            entities.persist(WishReviewOverride(workspace.id, run.id, wishes[index].id, option.id,
                CompatibilityVerdict.COMPATIBLE, output.verdicts.single { it.wishId == wishes[index].id }.verdict,
                "Recorded resolution", now, 1, source = source,
                answerId = if (source == "FAN_ANSWER") UUID.randomUUID() else null))
        }
        entities.flush()
        Fixture(workspace, option, wishes, run, output)
    }!!

    private data class Fixture(val workspace: Workspace, val option: Option, val wishes: List<Wish>, val run: AnalysisRun, val output: AnalysisOutput)

    @TestConfiguration(proxyBeanMethods = false)
    class Fakes {
        @Bean
        @Primary
        fun consistencyPayments() = InMemoryPaymentGateway()

        @Bean
        @Primary
        fun consistencyClock() = fixedClock(now)
    }

    companion object { private val now = Instant.parse("2026-01-01T00:00:00Z") }
}

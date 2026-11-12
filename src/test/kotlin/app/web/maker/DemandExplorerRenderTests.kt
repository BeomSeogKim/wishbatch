package app.web.maker

import app.campaign.demand.DemandExplorer
import app.campaign.model.AnalysisRun
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Money
import app.campaign.model.NeedAttribute
import app.campaign.model.NeedStrength
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.model.WishSource
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalyzedWish
import app.campaign.port.Proposal
import app.campaign.port.WishNeed
import app.campaign.port.WishVerdict
import app.campaign.review.WishReviewService
import app.campaign.workspace.WorkspaceService
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DemandExplorerRenderTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val workspaces: WorkspaceService,
    @Autowired private val entityManager: EntityManager,
    @Autowired private val mapper: JsonMapper,
    @Autowired private val reviews: WishReviewService,
) {
    @Test
    fun `offer renders compact demand and links to full evidence on the proposal`() {
        val workspace = workspaces.create()
        val options = workspaces.listOptions(workspace.token)
        val selected = options.first()
        val other = options[1]
        val source = Wish(workspace.id, "At most USD 10.00", WishSource.ONSITE,
            createdAt = Instant.EPOCH).also(entityManager::persist)
        val otherSource = Wish(workspace.id, "A print for my wall <script>alert('x')</script>", WishSource.ONSITE,
            createdAt = Instant.EPOCH).also(entityManager::persist)
        val wishes = listOf(source, otherSource)
        val output = AnalysisOutput(
            listOf(AnalyzedWish(source.id, WishKind.REQUEST,
                listOf(WishNeed(NeedStrength.HARD, NeedAttribute.PRICE, "Ignored", "At most USD 10.00")), Money(1, "USD")),
                AnalyzedWish(otherSource.id, WishKind.REQUEST, emptyList())),
            listOf(WishVerdict(source.id, selected.id, CompatibilityVerdict.COMPATIBLE, "Fits"),
                WishVerdict(otherSource.id, selected.id, CompatibilityVerdict.INCOMPATIBLE, "Different format"),
                WishVerdict(source.id, other.id, CompatibilityVerdict.INCOMPATIBLE, "Different format"),
                WishVerdict(otherSource.id, other.id, CompatibilityVerdict.COMPATIBLE, "Fits")),
            Proposal(selected.id, emptyMap(), emptyList()), null, emptyList(), emptyList(), emptyList(), options.map { it.id },
        )
        val run = AnalysisRun(workspace.id, "synthetic-model", "synthetic-input", mapper.writeValueAsString(output),
            createdAt = Instant.parse("2026-01-01T00:00:00Z")).also(entityManager::persist)
        entityManager.flush()
        val response = mvc.get("/w/${workspace.token}/maker/offer") { param("run", run.id.toString()); flashAttr("fields", mapOf("unitPrice" to "10.00", "minBatch" to "3")) }
            .andExpect { status { isOk() } }.andReturn()
        val html = response.response.contentAsString
        val demand = assertIs<DemandExplorer>(response.modelAndView!!.model["demand"])
        assertEquals(1000L, demand.wishes.single().price.maximumMinor)
        assertEquals(1, demand.points.single { it.priceMinor == 1000L }.compatible)
        assertEquals(0, demand.points.single { it.priceMinor == 1001L }.compatible)
        assertEquals(wishes.map { it.id }.toSet(), demand.alternatives.flatMap { it.wishIds }.toSet())
        assertContains(html, "Interested in this offer at each unit price")
        assertContains(html, "Interest in this offer at each candidate price")
        assertContains(html, "At most USD 10.00")
        assertContains(html, "run=${run.id}#wish-${source.id}")
        assertContains(html, "demand-compact")
        assertFalse(html.contains("<script>alert('x')</script>"))
        assertContains(html, "ag-charts-community@14.3.0/dist/umd/ag-charts-community.min.js")
        assertContains(html, "/maker/proposal?run=${run.id}")
        assertContains(html, "View full price and demand explorer")
        assertEquals(run.resultJson, entityManager.find(AnalysisRun::class.java, run.id).resultJson)

        val proposal = mvc.get("/w/${workspace.token}/maker/proposal") { param("run", run.id.toString()) }
            .andExpect { status { isOk() } }.andReturn()
        val proposalHtml = proposal.response.contentAsString
        assertEquals(demand.wishes, assertIs<DemandExplorer>(proposal.modelAndView!!.model["demand"]).wishes)
        assertContains(proposalHtml, "id=\"wish-workbench\"")
        assertContains(proposalHtml, "/maker/next-batch?run=${run.id}")
        assertContains(proposalHtml, "/maker/questions\"")
        assertContains(proposalHtml, "Review follow-up questions")
        assertContains(proposalHtml, "id=\"demand-explorer\"")

        reviews.decide(workspace.token, run.id, source.id, selected.id, CompatibilityVerdict.INCOMPATIBLE, "Different format")
        val reviewed = mvc.get("/w/${workspace.token}/maker/offer") { param("run", run.id.toString()) }
            .andExpect { status { isOk() } }.andReturn()
        assertEquals(0, assertIs<DemandExplorer>(reviewed.modelAndView!!.model["demand"]).compatible)
        assertContains(reviewed.response.contentAsString, "No Compatible wishes for this option.")
        val reviewedProposal = mvc.get("/w/${workspace.token}/maker/proposal") { param("run", run.id.toString()) }
            .andExpect { status { isOk() } }.andReturn()
        assertEquals(0, assertIs<DemandExplorer>(reviewedProposal.modelAndView!!.model["demand"]).compatible)
        val foreign = workspaces.create()
        mvc.get("/w/${foreign.token}/maker/offer") { param("run", run.id.toString()) }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `offer simulation uses submitted maker minimum and price after a server check`() {
        val workspace = workspaces.create()
        val result = mvc.get("/w/${workspace.token}/maker/offer")
            .andExpect { status { isOk() } }.andReturn()
        val option = assertIs<Option>(result.modelAndView!!.model["option"])
        val fields = mapOf("unitPrice" to "12.34", "minBatch" to option.maxCapacity.toString())
        val checked = mvc.get("/w/${workspace.token}/maker/offer") { flashAttr("fields", fields) }
            .andExpect { status { isOk() } }.andReturn()
        val demand = assertIs<DemandExplorer>(checked.modelAndView!!.model["demand"])
        assertEquals(option.maxCapacity, demand.minimum)
        assertEquals(true, demand.points.any { it.priceMinor == 1234L })
    }
}

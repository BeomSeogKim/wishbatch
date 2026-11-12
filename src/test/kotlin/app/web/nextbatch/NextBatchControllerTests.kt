package app.web.nextbatch

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.model.AnalysisRun
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.NeedAttribute
import app.campaign.model.NeedStrength
import app.campaign.model.WishKind
import app.campaign.nextbatch.NextBatch
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalyzedWish
import app.campaign.port.Proposal
import app.campaign.port.WishNeed
import app.campaign.port.WishVerdict
import app.campaign.review.WishReviewService
import jakarta.persistence.EntityManager
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import app.campaign.workspace.WorkspaceService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NextBatchControllerTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val workspaces: WorkspaceService,
    @Autowired private val runs: AnalysisRunService,
    @Autowired private val reviews: WishReviewService,
    @Autowired private val entityManager: EntityManager,
    @Autowired private val mapper: JsonMapper,
) {
    @Test
    fun `render scoped stored run and reject foreign runs`() {
        val first = workspaces.create()
        val second = workspaces.create()
        val run = assertNotNull(runs.latest(first.token))
        val html = mvc.get("/w/${first.token}/maker/next-batch?run=${run.run.id}").andExpect {
            status { isOk() }
            view { name("nextbatch/page") }
        }.andReturn().response.contentAsString
        assertContains(html, "Possible following batch")
        assertContains(html, "not a promise of another batch")
        mvc.get("/w/${second.token}/maker/next-batch?run=${run.run.id}").andExpect { status { isNotFound() } }
        mvc.get("/w/invalid/maker/next-batch").andExpect { status { isNotFound() } }
    }

    @Test
    fun `maker verdicts change candidate counts and evidence after reload`() {
        val workspace = workspaces.create()
        val token = workspace.token
        val options = workspaces.listOptions(token).take(2)
        val chosen = options[0]
        val candidate = options[1]
        val first = workspaces.addWish(token, "First distinct quote")
        val second = workspaces.addWish(token, "Second distinct quote")
        val third = workspaces.addWish(token, "Third distinct quote")
        val wishes = listOf(first, second, third)
        val output = AnalysisOutput(
            wishes = wishes.map { AnalyzedWish(it.id, WishKind.REQUEST,
                listOf(WishNeed(NeedStrength.HARD, NeedAttribute.FORMAT, "format", it.text))) },
            verdicts = listOf(
                WishVerdict(first.id, chosen.id, CompatibilityVerdict.INCOMPATIBLE, "Original"),
                WishVerdict(first.id, candidate.id, CompatibilityVerdict.COMPATIBLE, "Original"),
                WishVerdict(second.id, chosen.id, CompatibilityVerdict.COMPATIBLE, "Original"),
                WishVerdict(second.id, candidate.id, CompatibilityVerdict.COMPATIBLE, "Original"),
                WishVerdict(third.id, chosen.id, CompatibilityVerdict.INCOMPATIBLE, "Original"),
                WishVerdict(third.id, candidate.id, CompatibilityVerdict.INCOMPATIBLE, "Original"),
            ),
            proposal = Proposal(chosen.id, emptyMap(), emptyList()), noProposalReason = null,
            notServed = emptyList(), questionsForMaker = emptyList(), alternatives = emptyList(),
            optionIds = options.map { it.id },
        )
        val run = AnalysisRun(workspace.id, "fixture", "fixture", mapper.writeValueAsString(output),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"))
        entityManager.persist(run)
        entityManager.flush()
        entityManager.clear()

        fun page(): Pair<NextBatch, String> {
            val response = mvc.get("/w/$token/maker/next-batch?run=${run.id}").andExpect { status { isOk() } }.andReturn()
            return (response.modelAndView!!.model["nextBatch"] as NextBatch) to response.response.contentAsString
        }
        val (before, beforeHtml) = page()
        assertEquals(listOf(first.id), before.candidates.single().evidence.map { it.wishId })
        assertContains(beforeHtml, "First distinct quote")
        assertFalse(beforeHtml.contains("Second distinct quote"))
        assertFalse(beforeHtml.contains("Third distinct quote"))

        reviews.decide(token, run.id, first.id, candidate.id, CompatibilityVerdict.INCOMPATIBLE, "Maker review")
        reviews.decide(token, run.id, second.id, chosen.id, CompatibilityVerdict.INCOMPATIBLE, "Maker review")
        reviews.decide(token, run.id, third.id, candidate.id, CompatibilityVerdict.COMPATIBLE, "Maker review")
        entityManager.flush()
        entityManager.clear()

        val stored = assertNotNull(runs.find(token, run.id))
        assertEquals(output, stored.originalOutput)
        val (after, afterHtml) = page()
        val evidence = after.candidates.single().evidence
        assertEquals(2, evidence.size)
        assertEquals(setOf(second.id, third.id), evidence.map { it.wishId }.toSet())
        assertEquals(setOf("Second distinct quote", "Third distinct quote"), evidence.flatMap { it.quotes }.toSet())
        assertContains(afterHtml, "<strong>2</strong> compatible requests")
        assertContains(afterHtml, "Second distinct quote")
        assertContains(afterHtml, "Third distinct quote")
        assertFalse(afterHtml.contains("First distinct quote"))
    }

}

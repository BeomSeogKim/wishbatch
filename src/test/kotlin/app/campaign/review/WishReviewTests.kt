package app.campaign.review

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.model.AnalysisRun
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.WishKind
import app.campaign.port.AnalysisOutput
import app.campaign.port.SecondReading
import app.campaign.port.WishReadingComparison
import app.campaign.rules.optionWishCounts
import app.campaign.workspace.WorkspaceService
import app.web.security.securePost
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
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WishReviewTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val workspaces: WorkspaceService,
    @Autowired private val runs: AnalysisRunService,
    @Autowired private val reviews: WishReviewService,
    @Autowired private val mapper: JsonMapper,
    @Autowired private val entityManager: EntityManager,
) {
    @Test
    fun `disagreement is reviewable and maker decisions survive reload without changing the run`() {
        val token = workspaces.create().token
        val original = runs.latest(token)!!
        val option = original.output.proposal!!.optionId
        val first = original.output.verdicts.first { it.optionId == option && it.verdict == CompatibilityVerdict.COMPATIBLE }
        val output = original.output.copy(
            verdicts = original.output.verdicts.map {
                if (it == first) it.copy(verdict = CompatibilityVerdict.CONDITIONAL, reason = "The readings disagree.") else it
            },
            secondReading = SecondReading(39, 40, listOf(WishReadingComparison(
                first.wishId, option, WishKind.REQUEST, WishKind.REQUEST,
                CompatibilityVerdict.COMPATIBLE, CompatibilityVerdict.INCOMPATIBLE,
            )), batchCompositionChanged = true),
        )
        val run = store(token, output)
        val snapshot = run.resultJson
        val before = optionWishCounts(output).single { it.optionId == option }
        var html = page(token, run.id)
        assertContains(html, "97.5%")
        assertContains(html, "Needs your eyes")
        assertContains(html, "First reading: Compatible")
        assertContains(html, "Second reading: Not served")

        post(token, run.id, first.wishId, option, "COMPATIBLE", "Catalog meets the request.")
        entityManager.flush()
        entityManager.clear()
        var reviewed = runs.find(token, run.id)!!
        val saved = reviewed.overrides.single()
        assertEquals(run.id, saved.runId)
        assertEquals(first.wishId, saved.wishId)
        assertEquals(option, saved.optionId)
        assertEquals("MAKER", saved.source)
        assertEquals(CompatibilityVerdict.CONDITIONAL, saved.previousVerdict)
        assertEquals(1, saved.revision)
        assertEquals(snapshot, reviewed.run.resultJson)
        assertEquals(output, reviewed.originalOutput)
        assertEquals(before.compatible + 1, optionWishCounts(reviewed.output).single { it.optionId == option }.compatible)
        html = page(token, run.id)
        assertFalse(html.contains("Needs your eyes"))
        assertContains(html, "Maker override")
        assertContains(html, "Catalog meets the request.")
        assertContains(html, "97.5%")

        post(token, run.id, first.wishId, option, "INCOMPATIBLE", "The requested finish cannot be supplied.")
        entityManager.flush()
        entityManager.clear()
        reviewed = runs.find(token, run.id)!!
        assertEquals(listOf(1L, 2L), reviewed.overrides.map { it.revision })
        assertEquals(CompatibilityVerdict.COMPATIBLE, reviewed.overrides.last().previousVerdict)
        assertEquals(before.notServed + 1, optionWishCounts(reviewed.output).single { it.optionId == option }.notServed)
        assertTrue(reviewed.output.notServed.any { first.wishId in it.wishIds })
        assertEquals(snapshot, reviewed.run.resultJson)
        assertEquals(original.output, runs.find(token, original.run.id)!!.output)
        assertTrue(runs.find(token, original.run.id)!!.overrides.isEmpty())
    }

    @Test
    fun `maker can move agreed and nonrequest wishes for any option with per-option decisions`() {
        val token = workspaces.create().token
        val stored = runs.latest(token)!!
        val option = stored.output.optionIds.first()
        val otherOption = stored.output.optionIds.last()
        val compatible = stored.output.verdicts.first { it.optionId == option && it.verdict == CompatibilityVerdict.COMPATIBLE }
        val nonrequest = stored.output.wishes.first { it.kind == WishKind.NOT_A_REQUEST }
        reviews.decide(token, stored.run.id, compatible.wishId, option, CompatibilityVerdict.INCOMPATIBLE, "Different format required.")
        reviews.decide(token, stored.run.id, nonrequest.wishId, otherOption, CompatibilityVerdict.COMPATIBLE, "This is a product request.")
        entityManager.flush()
        entityManager.clear()
        val result = runs.find(token, stored.run.id)!!
        assertEquals(CompatibilityVerdict.INCOMPATIBLE, result.output.verdicts.single {
            it.wishId == compatible.wishId && it.optionId == option
        }.verdict)
        assertEquals(WishKind.REQUEST, result.output.wishes.single { it.wishId == nonrequest.wishId }.kind)
        assertEquals(CompatibilityVerdict.COMPATIBLE, result.output.verdicts.single {
            it.wishId == nonrequest.wishId && it.optionId == otherOption
        }.verdict)
        assertEquals(CompatibilityVerdict.CONDITIONAL, result.output.verdicts.single {
            it.wishId == nonrequest.wishId && it.optionId == option
        }.verdict)
        assertEquals(stored.output.verdicts.single { it.wishId == compatible.wishId && it.optionId == otherOption },
            result.output.verdicts.single { it.wishId == compatible.wishId && it.optionId == otherOption })
    }

    @Test
    fun `wrong workspace and references cannot write decisions and invalid input is rejected`() {
        val token = workspaces.create().token
        val stored = runs.latest(token)!!
        val wish = stored.output.wishes.first().wishId
        val option = stored.output.optionIds.first()
        val foreignToken = workspaces.create().token
        for ((requestToken, run, requestWish, requestOption) in listOf(
            listOf(foreignToken, stored.run.id, wish, option),
            listOf("missing", stored.run.id, wish, option),
            listOf(token, UUID.randomUUID(), wish, option),
            listOf(token, stored.run.id, UUID.randomUUID(), option),
            listOf(token, stored.run.id, wish, UUID.randomUUID()),
        )) {
            mvc.securePost("/w/$requestToken/maker/proposal/review") {
                param("run", run.toString()); param("wish", requestWish.toString()); param("option", requestOption.toString())
                param("verdict", "COMPATIBLE"); param("reason", "Review decision.")
            }.andExpect { status { isNotFound() } }
        }
        for ((verdict, reason) in listOf("CONDITIONAL" to "Unresolved", "COMPATIBLE" to "  ", "INCOMPATIBLE" to "x".repeat(501))) {
            mvc.securePost("/w/$token/maker/proposal/review") {
                param("run", stored.run.id.toString()); param("wish", wish.toString()); param("option", option.toString())
                param("verdict", verdict); param("reason", reason)
            }.andExpect { status { isBadRequest() } }
        }
        assertTrue(reviews.history(stored.run.workspaceId, stored.run.id).isEmpty())
    }

    @Test
    fun `legacy seed and no proposal still allow review without inventing an agreement rate`() {
        val token = workspaces.create().token
        val original = runs.latest(token)!!
        assertEquals(null, original.output.secondReading)
        assertContains(page(token, original.run.id), "No second reading is recorded for this result.")
        val run = store(token, original.output.copy(proposal = null, noProposalReason = "No proposal.", notServed = emptyList()))
        val html = page(token, run.id)
        assertContains(html, "Review each wish.")
        for (wish in original.output.wishes) assertContains(html, "id=\"wish-${wish.wishId}\"")
        assertContains(html, "Save decision")
    }

    private fun page(token: String, run: UUID): String = mvc.get("/w/$token/maker/proposal") {
        param("run", run.toString())
    }.andExpect { status { isOk() } }.andReturn().response.contentAsString

    private fun post(token: String, run: UUID, wish: UUID, option: UUID, verdict: String, reason: String) {
        mvc.securePost("/w/$token/maker/proposal/review") {
            param("run", run.toString()); param("wish", wish.toString()); param("option", option.toString())
            param("verdict", verdict); param("reason", reason)
        }.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/w/$token/maker/proposal?run=$run#wish-$wish")
        }
    }

    private fun store(token: String, output: AnalysisOutput): AnalysisRun {
        val previous = assertNotNull(runs.latest(token)).run
        return AnalysisRun(previous.workspaceId, "test-model", previous.inputHash, mapper.writeValueAsString(output),
            createdAt = Instant.parse("2090-01-01T00:00:00Z")).also {
            entityManager.persist(it)
            entityManager.flush()
        }
    }
}

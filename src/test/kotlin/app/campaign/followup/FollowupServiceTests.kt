package app.campaign.followup

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.model.AnalysisRun
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.NeedAttribute
import app.campaign.model.NeedStrength
import app.campaign.model.Option
import app.campaign.model.WishKind
import app.campaign.port.AnalysisFailure
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalyzedWish
import app.campaign.port.Proposal
import app.campaign.port.WishNeed
import app.campaign.port.WishVerdict
import app.campaign.review.InvalidWishReviewException
import app.campaign.review.WishReviewService
import app.campaign.rules.optionWishCounts
import app.campaign.workspace.WorkspaceNotFoundException
import app.campaign.workspace.WorkspaceService
import app.web.security.securePost
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(FollowupServiceTests.DraftConfiguration::class)
class FollowupServiceTests @Autowired constructor(
    private val followups: FollowupService,
    private val workspaces: WorkspaceService,
    private val runs: AnalysisRunService,
    private val reviews: WishReviewService,
    private val mapper: JsonMapper,
    private val mvc: MockMvc,
    private val entityManager: EntityManager,
    private val drafter: OfflineDrafter,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)
    private val calls get() = drafter.calls

    @BeforeEach
    fun draftVariants() {
        drafter.calls = 0
        drafter.failure = null
    }

    class OfflineDrafter : FollowupDrafter {
        var calls = 0
        var failure: AnalysisFailure? = null
        override fun draft(option: Option, output: AnalysisOutput): FollowupDraftResult {
            calls++
            failure?.let { return FollowupDraftResult.Failed(it) }
            val drafts = option.allowedVariants.filterKeys { it in setOf("finish", "size") }.map { (key, choices) ->
                FollowupDraft("Which $key meets your condition?", if (key == "finish") NeedAttribute.FINISH else NeedAttribute.SIZE,
                    key, choices, output.wishes.map { it.wishId })
            }
            return FollowupDraftResult.Drafted(drafts, "offline-model")
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class DraftConfiguration {
        @Bean
        @Primary
        fun offlineDrafter() = OfflineDrafter()
    }

    @Test
    fun `approved fan answer updates effective count with provenance and preserves original evidence`() {
        val fixture = fixture()
        assertTrue(followups.fanQuestions(fixture.token, fixture.wish).isEmpty())
        val question = draft(fixture)
        assertEquals(1, calls)
        assertTrue(followups.fanQuestions(fixture.token, fixture.wish).isEmpty())
        followups.draft(fixture.token)
        assertEquals(1, calls)
        followups.approve(fixture.token, question.id, "Which finish works for you?", question.choices)
        assertEquals("Which finish works for you?", followups.fanQuestions(fixture.token, fixture.wish).single().text)
        val answer = followups.answer(fixture.token, question.id, fixture.wish, "glossy")
        var stored = assertNotNull(runs.find(fixture.token, fixture.run.id))
        val decision = stored.overrides.single()
        assertEquals("FAN_ANSWER", decision.source)
        assertEquals(answer.id, decision.answerId)
        assertEquals(CompatibilityVerdict.CONDITIONAL, decision.previousVerdict)
        assertEquals(CompatibilityVerdict.COMPATIBLE, decision.verdict)
        assertEquals(1L, decision.revision)
        assertEquals(1, optionWishCounts(stored.output).single().compatible)
        assertEquals(0, optionWishCounts(stored.output).single().depends)
        assertEquals(fixture.run.resultJson, stored.run.resultJson)
        assertEquals(fixture.output, stored.originalOutput)
        assertEquals("Only matte", workspaces.listWishes(fixture.token).single { it.id == fixture.wish }.text)
        assertEquals(answer.id, followups.answers(fixture.token, fixture.wish).single().id)
        assertEquals(answer.id, followups.answer(fixture.token, question.id, fixture.wish, "glossy").id)
        assertEquals(decision.id, reviews.recordFanResolution(fixture.token, answer.id)?.id)
        assertEquals(1, reviews.history(fixture.run.workspaceId, fixture.run.id).size)
        val fanLabel = mvc.get("/w/${fixture.token}/maker/proposal") { param("run", fixture.run.id.toString()) }
            .andReturn().response.contentAsString
        assertContains(fanLabel, ">Fan answer</strong>")
        assertFalse(fanLabel.contains(">Maker override</strong>"))

        val makerReason = "Maker review <script>alert('wish')</script>"
        reviews.decide(fixture.token, fixture.run.id, fixture.wish, fixture.option.id,
            CompatibilityVerdict.INCOMPATIBLE, makerReason)
        followups.answer(fixture.token, question.id, fixture.wish, "glossy")
        reviews.recordFanResolution(fixture.token, answer.id)
        stored = assertNotNull(runs.find(fixture.token, fixture.run.id))
        assertEquals(2, stored.overrides.size)
        assertEquals("MAKER", stored.overrides.last().source)
        assertNull(stored.overrides.last().answerId)
        assertEquals(CompatibilityVerdict.INCOMPATIBLE, stored.output.verdicts.single().verdict)
        assertEquals(listOf("FAN_ANSWER", "MAKER"), stored.overrides.map { it.source })
        assertEquals(makerReason, stored.overrides.last().reason)
        val makerLabel = mvc.get("/w/${fixture.token}/maker/proposal") { param("run", fixture.run.id.toString()) }
            .andReturn().response.contentAsString
        assertContains(makerLabel, ">Maker override</strong>")
        assertContains(makerLabel, "&lt;script&gt;")
        assertFalse(makerLabel.contains(makerReason))
        assertEquals(1, calls)
    }

    @Test
    fun `one shared approved question resolves fans independently`() {
        val fixture = fixture(secondWish = true)
        val question = draft(fixture)
        followups.approve(fixture.token, question.id, question.text, question.choices)
        for (wish in question.wishIds) followups.answer(fixture.token, question.id, wish, "matte")
        val stored = assertNotNull(runs.find(fixture.token, fixture.run.id))
        assertEquals(2, stored.overrides.size)
        assertEquals(2, optionWishCounts(stored.output).single().compatible)
        assertTrue(stored.overrides.all { it.source == "FAN_ANSWER" && it.answerId != null })
    }

    @Test
    fun `unresolved commercial condition records answer without increasing compatible demand`() {
        val fixture = fixture(extraNeed = WishNeed(NeedStrength.UNKNOWN, NeedAttribute.PRICE, "Unresolved", "Only matte"))
        val question = draft(fixture)
        followups.approve(fixture.token, question.id, question.text, question.choices)
        val answer = followups.answer(fixture.token, question.id, fixture.wish, "matte")
        assertNull(reviews.recordFanResolution(fixture.token, answer.id))
        assertEquals(1, followups.answers(fixture.token, fixture.wish).size)
        assertEquals(1, optionWishCounts(runs.find(fixture.token, fixture.run.id)!!.output).single().depends)
        assertTrue(reviews.history(fixture.run.workspaceId, fixture.run.id).isEmpty())
    }

    @Test
    fun `partial answer replay after another answer completes the wish is idempotent`() {
        val fixture = fixture(extraNeed = WishNeed(NeedStrength.HARD, NeedAttribute.SIZE, "small", "Only matte"))
        followups.draft(fixture.token)
        val questions = followups.makerPage(fixture.token).questions
        assertEquals(2, questions.size)
        questions.forEach { followups.approve(fixture.token, it.id, it.text, it.choices) }
        val first = followups.answer(fixture.token, questions.first().id, fixture.wish, questions.first().choices.first())
        assertTrue(reviews.history(fixture.run.workspaceId, fixture.run.id).isEmpty())
        followups.answer(fixture.token, questions.last().id, fixture.wish, questions.last().choices.first())
        val decision = reviews.history(fixture.run.workspaceId, fixture.run.id).single()
        assertEquals(decision.id, reviews.recordFanResolution(fixture.token, first.id)?.id)
        assertEquals(1, reviews.history(fixture.run.workspaceId, fixture.run.id).size)
    }

    @Test
    fun `unapproved invented edited foreign hidden and stale answers cannot alter a run`() {
        val fixture = fixture()
        val question = draft(fixture)
        assertFailsWith<InvalidFollowupException> { followups.answer(fixture.token, question.id, fixture.wish, "matte") }
        assertFailsWith<InvalidFollowupException> {
            followups.approve(fixture.token, question.id, question.text, listOf("matte", "metallic"))
        }
        assertFailsWith<WorkspaceNotFoundException> {
            followups.approve(workspaces.create().token, question.id, question.text, question.choices)
        }
        followups.approve(fixture.token, question.id, question.text, question.choices)
        assertFailsWith<InvalidFollowupException> {
            followups.approve(fixture.token, question.id, "A different question?", question.choices)
        }
        assertFailsWith<InvalidFollowupException> { followups.answer(fixture.token, question.id, fixture.wish, "metallic") }
        val otherWish = workspaces.addWish(fixture.token, "A separate request")
        assertFailsWith<InvalidFollowupException> { followups.answer(fixture.token, question.id, otherWish.id, "matte") }
        assertFailsWith<WorkspaceNotFoundException> { followups.answer(workspaces.create().token, question.id, fixture.wish, "matte") }
        workspaces.setWishHidden(fixture.token, fixture.wish, true)
        assertFailsWith<WorkspaceNotFoundException> { followups.answer(fixture.token, question.id, fixture.wish, "matte") }
        workspaces.setWishHidden(fixture.token, fixture.wish, false)
        storeRun(fixture.run.workspaceId, fixture.output, Instant.parse("2091-01-01T00:00:00Z"))
        assertFailsWith<WorkspaceNotFoundException> { followups.answer(fixture.token, question.id, fixture.wish, "matte") }
        assertTrue(reviews.history(fixture.run.workspaceId, fixture.run.id).isEmpty())
    }

    @Test
    fun `later maker review rejects a first fan answer and rolls its persistence back`() {
        val fixture = fixture()
        val question = draft(fixture)
        followups.approve(fixture.token, question.id, question.text, question.choices)
        reviews.decide(fixture.token, fixture.run.id, fixture.wish, fixture.option.id,
            CompatibilityVerdict.INCOMPATIBLE, "This condition cannot be met.")
        assertFailsWith<InvalidWishReviewException> { followups.answer(fixture.token, question.id, fixture.wish, "matte") }
        assertTrue(followups.answers(fixture.token, fixture.wish).isEmpty())
        assertEquals(listOf("MAKER"), reviews.history(fixture.run.workspaceId, fixture.run.id).map { it.source })
        assertFailsWith<WorkspaceNotFoundException> { reviews.recordFanResolution(fixture.token, UUID.randomUUID()) }
    }

    @Test
    fun `maker approval and fan answer work through rendered forms with genuine csrf`() {
        val fixture = fixture()
        val base = "/w/${fixture.token}"
        assertContains(mvc.get("$base/maker/questions").andReturn().response.contentAsString, "Draft follow-up questions")
        mvc.securePost("$base/maker/questions").andExpect { status { is3xxRedirection() } }
        val question = followups.makerPage(fixture.token).questions.single()
        mvc.securePost("$base/maker/questions/${question.id}/approve") {
            param("text", "Which finish meets your condition?")
            param("choices", "matte", "glossy")
        }.andExpect { status { is3xxRedirection() } }
        val fan = mvc.get("$base/questions") { param("wish", fixture.wish.toString()) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(fan, "name=\"choice\" value=\"matte\"")
        assertContains(fan, "name=\"_csrf\"")
        mvc.securePost("$base/questions/${question.id}") {
            param("wish", fixture.wish.toString())
            param("choice", "matte")
        }.andExpect { status { is3xxRedirection() }; redirectedUrl("$base/questions?wish=${fixture.wish}") }
        val saved = mvc.get("$base/questions") { param("wish", fixture.wish.toString()) }
            .andReturn().response.contentAsString
        assertContains(saved, "Your saved answer:")
        assertContains(saved, "Source: fan answer.")
        assertEquals(1, optionWishCounts(runs.find(fixture.token, fixture.run.id)!!.output).single().compatible)
        assertEquals(1, calls)
    }

    @Test
    fun `review callback validates persisted question approval scope and exact answer choice`() {
        val fixture = fixture()
        val question = draft(fixture)
        val foreign = fixture()
        fun saveAnswer(workspace: UUID = fixture.run.workspaceId, choice: String = "matte"): FollowupAnswerRecord = transaction.execute {
            FollowupAnswerRecord(workspace, fixture.run.id, fixture.option.id, question.id, fixture.wish, choice,
                Instant.parse("2090-01-01T00:00:00Z"))
                .also { entityManager.persist(it) }
        }!!
        val unapproved = saveAnswer()
        assertFailsWith<InvalidWishReviewException> { reviews.recordFanResolution(fixture.token, unapproved.id) }
        assertFailsWith<WorkspaceNotFoundException> { reviews.recordFanResolution(foreign.token, unapproved.id) }
        transaction.execute { entityManager.remove(entityManager.find(FollowupAnswerRecord::class.java, unapproved.id)) }
        followups.approve(fixture.token, question.id, question.text, question.choices)
        val invented = saveAnswer(choice = "metallic")
        assertFailsWith<InvalidWishReviewException> { reviews.recordFanResolution(fixture.token, invented.id) }
        assertTrue(reviews.history(fixture.run.workspaceId, fixture.run.id).isEmpty())
    }

    @Test
    fun `provider failure is retained without repeated calls and absent configuration can recover`() {
        val fixture = fixture()
        drafter.failure = AnalysisFailure.TIMEOUT
        repeat(2) { assertEquals(FollowupDraftResult.Failed(AnalysisFailure.TIMEOUT), followups.draft(fixture.token)) }
        assertEquals(1, calls)
        assertEquals("TIMEOUT", followups.makerPage(fixture.token).failure)
        assertTrue(followups.makerPage(fixture.token).questions.isEmpty())
        val unconfigured = fixture()
        drafter.failure = AnalysisFailure.NOT_CONFIGURED
        followups.draft(unconfigured.token)
        assertNull(followups.makerPage(unconfigured.token).failure)
        draftVariants()
        assertTrue(followups.draft(unconfigured.token) is FollowupDraftResult.Drafted)
    }

    private fun draft(fixture: Fixture): FollowupQuestion {
        followups.draft(fixture.token)
        return followups.makerPage(fixture.token).questions.single()
    }

    private data class Fixture(val token: String, val run: AnalysisRun, val option: Option, val wish: UUID, val output: AnalysisOutput)

    private fun fixture(extraNeed: WishNeed? = null, secondWish: Boolean = false): Fixture {
        val workspace = workspaces.create()
        val originalOption = workspaces.listOptions(workspace.token).single { it.allowedVariants.containsKey("finish") }
        val option = if (extraNeed?.attribute == NeedAttribute.SIZE) transaction.execute {
            Option(workspace.id, "Synthetic print", "print", emptyMap(),
                originalOption.allowedVariants + ("size" to listOf("small", "large")),
                500, "USD", 3, 10, "Maker delivery terms", "US").also { entityManager.persist(it) }
        }!! else originalOption
        val wish = workspaces.addWish(workspace.token, "Only matte")
        val wishes = listOf(wish) + if (secondWish) listOf(workspaces.addWish(workspace.token, "Only matte")) else emptyList()
        val needs = listOf(WishNeed(NeedStrength.HARD, NeedAttribute.FINISH, "matte", "Only matte")) + listOfNotNull(extraNeed)
        val output = AnalysisOutput(wishes.map { AnalyzedWish(it.id, WishKind.REQUEST, needs) },
            wishes.map { WishVerdict(it.id, option.id, CompatibilityVerdict.CONDITIONAL, "Confirm finish.") },
            Proposal(option.id, emptyMap(), emptyList()), null, emptyList(), emptyList(), emptyList(), listOf(option.id))
        return Fixture(workspace.token, storeRun(workspace.id, output, Instant.parse("2090-01-01T00:00:00Z")), option, wish.id, output)
    }

    private fun storeRun(workspace: UUID, output: AnalysisOutput, at: Instant): AnalysisRun = transaction.execute {
        AnalysisRun(workspace, "offline-model", "offline-hash", mapper.writeValueAsString(output), createdAt = at)
            .also { entityManager.persist(it) }
    }!!
}

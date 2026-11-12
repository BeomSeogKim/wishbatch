package app.campaign.replies

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.model.AnalysisRun
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.WishKind
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalyzedWish
import app.campaign.port.NotServedGroup
import app.campaign.port.WishVerdict
import app.campaign.review.WishReviewService
import app.campaign.rules.analysisInputHash
import app.campaign.workspace.WorkspaceNotFoundException
import app.campaign.workspace.WorkspaceService
import app.web.security.securePost
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(ReplyServiceTests.Configuration::class)
class ReplyServiceTests(
    @Autowired private val replies: ReplyService,
    @Autowired private val workspaces: WorkspaceService,
    @Autowired private val runs: AnalysisRunService,
    @Autowired private val reviews: WishReviewService,
    @Autowired private val drafter: FakeReplyDrafter,
    @Autowired private val mapper: JsonMapper,
    @Autowired private val em: EntityManager,
    @Autowired private val mvc: MockMvc,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    @BeforeEach
    fun reset() { drafter.calls = 0; drafter.failure = false; drafter.fabricate = false }

    @Test
    fun `drafts are maker only and edited approvals are idempotent and restricted to relevant wishes`() {
        val fixture = fixture()
        val before = replies.maker(fixture.token, fixture.run.id)
        assertEquals("READY", before.status)
        assertNull(replies.approvedForWish(fixture.token, fixture.run.id, fixture.wishes.first()))
        val drafted = replies.generate(fixture.token, fixture.run.id, before.evidenceHash)
        assertEquals("DRAFTED", drafted.status)
        assertEquals(2, drafted.groups.size)
        assertEquals(1, drafter.calls)
        assertTrue(drafted.groups.none { it.approved })
        val group = drafted.groups.first { fixture.wishes.first() in it.evidence.sources.map(ReplySource::wishId) }
        assertEquals(2, group.evidence.sources.size)
        val fanPath = "/w/${fixture.token}/wishes/${fixture.wishes.first()}/reply"
        val unapproved = mvc.get(fanPath) { param("run", fixture.run.id.toString()) }
            .andExpect { status { isNoContent() } }.andReturn().response
        assertFalse(unapproved.contentAsString.contains(group.draft!!))
        assertFalse(unapproved.contentAsString.contains("<script>"))
        val edited = "Thanks for your wish. " + group.evidence.reasons.single()
        approve(fixture, group, edited)
        val approved = assertNotNull(replies.approvedForWish(fixture.token, fixture.run.id, fixture.wishes.first()))
        assertEquals(edited, approved.body)
        assertContains(approved.quote, "$12")
        assertFalse(approved.body.contains("12"))
        approve(fixture, group, edited)
        assertEquals(approved, replies.approvedForWish(fixture.token, fixture.run.id, fixture.wishes.first()))
        val html = mvc.get(fanPath) { param("run", fixture.run.id.toString()) }.andExpect {
            status { isOk() }; header { string("Cache-Control", "no-store") }
        }.andReturn().response.contentAsString
        assertContains(html, "Maker-approved reply")
        assertContains(html, "&lt;script&gt;")
        assertFalse(html.contains("<script>fanMarker()"))
        assertFalse(html.contains("Another fan private phrase"))
        assertFalse(html.contains("Different group private phrase"))
        assertNull(replies.approvedForWish(fixture.token, fixture.run.id, fixture.wishes.last()))
        replies.generate(fixture.token, fixture.run.id, before.evidenceHash)
        assertEquals(1, drafter.calls)
    }

    @Test
    fun `unsupported maker text commercial terms numeric dates and future promises cannot be approved`() {
        val fixture = fixture()
        val view = drafted(fixture)
        val group = view.groups.first()
        for (body in listOf("Made up reason.", "We will make it next month.", "$12", "Twenty items.", "May delivery.",
            group.draft + " Available later.", "Thanks for your wish. Wrong format reason.")) {
            assertFailsWith<UnsafeReplyException> {
                replies.approve(fixture.token, fixture.run.id, group.evidence.groupId, view.evidenceHash, body)
            }
        }
        assertTrue(view.groups.all { groupView -> groupView.evidence.sources.all {
            replies.approvedForWish(fixture.token, fixture.run.id, it.wishId) == null
        } })
        for (reason in listOf("Twenty stickers are included.", "It costs twenty pounds.", "The fourth batch is planned.",
            "We'll make it.", "Delivery in thirteen days.", "The price is ten euros.", "Available 2027-01-01.", "Only €five.",
            "A twelfth item.", "A thirtieth design.", "Priced in pounds.", "We aim to include it.")) {
            assertFalse(ReplySafety.isSafeReason(reason), reason)
        }
    }

    @Test
    fun `foreign workspaces runs groups wishes and changed client evidence cannot read or approve`() {
        val own = fixture()
        val other = fixture()
        val view = drafted(own)
        val group = view.groups.first()
        assertFailsWith<WorkspaceNotFoundException> { replies.maker(other.token, own.run.id) }
        assertFailsWith<WorkspaceNotFoundException> {
            replies.approve(other.token, own.run.id, group.evidence.groupId, view.evidenceHash, group.draft!!)
        }
        assertNull(replies.approvedForWish(own.token, own.run.id, other.wishes.first()))
        for ((id, hash) in listOf("foreign" to view.evidenceHash, group.evidence.groupId to "changed")) {
            assertFailsWith<InvalidReplyEvidenceException> { replies.approve(own.token, own.run.id, id, hash, group.draft!!) }
        }
        mvc.get("/w/${other.token}/wishes/${own.wishes.first()}/reply") { param("run", own.run.id.toString()) }
            .andExpect { status { isNotFound() } }
        mvc.post("/w/${own.token}/maker/replies/generate") {
            param("run", own.run.id.toString()); param("evidenceHash", view.evidenceHash)
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `hidden changed sources new analysis and effective review changes invalidate approvals`() {
        for (change in listOf("hidden", "text", "review", "run")) {
            val fixture = fixture()
            val view = drafted(fixture)
            val group = view.groups.first()
            val source = group.evidence.sources.first()
            replies.approve(fixture.token, fixture.run.id, group.evidence.groupId, view.evidenceHash, group.draft!!)
            when (change) {
                "hidden" -> workspaces.setWishHidden(fixture.token, source.wishId, true)
                "text" -> transaction.executeWithoutResult {
                    em.createNativeQuery("update wishes set text = :text where id = :id")
                        .setParameter("text", "Changed exact source").setParameter("id", source.wishId).executeUpdate()
                }
                "review" -> reviews.decide(fixture.token, fixture.run.id, source.wishId,
                    runs.find(fixture.token, fixture.run.id)!!.output.proposal!!.optionId,
                    CompatibilityVerdict.COMPATIBLE, "This format matches.")
                "run" -> transaction.executeWithoutResult {
                    em.persist(AnalysisRun(fixture.run.workspaceId, "test", fixture.run.inputHash, fixture.run.resultJson,
                        createdAt = Instant.parse("2091-01-01T00:00:00Z")))
                }
            }
            assertNull(replies.approvedForWish(fixture.token, fixture.run.id, source.wishId), change)
            assertFailsWith<InvalidReplyEvidenceException> {
                replies.approve(fixture.token, fixture.run.id, group.evidence.groupId, view.evidenceHash, group.draft)
            }
        }
    }

    @Test
    fun `malformed groups are rejected and provider failure or fabricated body never repeats calls`() {
        for (mode in listOf("failure", "fabricated")) {
            val fixture = fixture()
            drafter.failure = mode == "failure"
            drafter.fabricate = mode == "fabricated"
            val view = replies.maker(fixture.token, fixture.run.id)
            val result = replies.generate(fixture.token, fixture.run.id, view.evidenceHash)
            assertEquals(if (drafter.failure) "PROVIDER_FAILURE" else "INVALID_OUTPUT", result.status)
            assertTrue(result.groups.all { it.draft == null })
            val calls = drafter.calls
            replies.generate(fixture.token, fixture.run.id, view.evidenceHash)
            assertEquals(calls, drafter.calls)
        }
        val fixture = fixture()
        transaction.executeWithoutResult {
            val output = mapper.readValue(fixture.run.resultJson, AnalysisOutput::class.java)
            val invalid = output.copy(notServed = listOf(NotServedGroup("Fabricated", listOf(UUID.randomUUID()), "Wrong format.")))
            em.persist(AnalysisRun(fixture.run.workspaceId, "test", fixture.run.inputHash, mapper.writeValueAsString(invalid),
                createdAt = Instant.parse("2091-01-01T00:00:00Z")))
        }
        assertFailsWith<InvalidReplyEvidenceException> { replies.maker(fixture.token, runs.latest(fixture.token)!!.run.id) }
    }

    @Test
    fun `maker forms render safe evidence accept edit approval and show empty stale and loading states`() {
        val fixture = fixture()
        val before = replies.maker(fixture.token, fixture.run.id)
        val path = "/w/${fixture.token}/maker/replies"
        val page = mvc.get(path) { param("run", fixture.run.id.toString()) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(page, "Draft replies")
        assertContains(page, "Drafting replies. Please wait.")
        assertContains(page, "name=\"_csrf\"")
        mvc.securePost("$path/generate") {
            param("run", fixture.run.id.toString()); param("evidenceHash", before.evidenceHash)
        }.andExpect { status { is3xxRedirection() } }
        val group = replies.maker(fixture.token, fixture.run.id).groups.first()
        mvc.securePost("$path/approve") {
            param("run", fixture.run.id.toString()); param("group", group.evidence.groupId)
            param("evidenceHash", before.evidenceHash); param("body", "Unsafe fabricated reason.")
        }.andExpect { status { isBadRequest() }; content { string(org.hamcrest.Matchers.containsString("Keep the exact analysis reason")) } }
        approve(fixture, group, group.evidence.reasons.single())
        assertTrue(replies.maker(fixture.token, fixture.run.id).groups.any { it.approved })
        workspaces.addWish(fixture.token, "A new source after analysis")
        mvc.get(path) { param("run", fixture.run.id.toString()) }.andExpect { status { isConflict() } }

        val empty = fixture(empty = true)
        assertEquals("EMPTY", replies.maker(empty.token, empty.run.id).status)
        assertContains(mvc.get("/w/${empty.token}/maker/replies") { param("run", empty.run.id.toString()) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString, "No request groups need a reply")
    }

    private data class Fixture(val token: String, val run: AnalysisRun, val wishes: List<UUID>)
    private fun fixture(empty: Boolean = false): Fixture {
        val workspace = workspaces.create()
        val added = listOf("I asked for a poster under $12 <script>fanMarker()</script>",
            "Another fan private phrase: a poster please", "Different group private phrase: glossy finish").map {
            workspaces.addWish(workspace.token, it).id
        }
        val seed = runs.latest(workspace.token)!!
        val wishes = workspaces.listWishes(workspace.token, false)
        val options = workspaces.listOptions(workspace.token)
        val option = seed.output.proposal!!.optionId
        val reasons = listOf("This batch uses a different format.", "This batch has a different finish.")
        val groups = if (empty) emptyList() else listOf(NotServedGroup("Format", added.take(2), reasons[0]),
            NotServedGroup("Finish", added.takeLast(1), reasons[1]))
        val output = AnalysisOutput(wishes.map { AnalyzedWish(it.id, WishKind.REQUEST, emptyList()) },
            wishes.flatMap { wish -> options.map { product ->
                val group = groups.find { wish.id in it.wishIds && product.id == option }
                WishVerdict(wish.id, product.id, if (group == null) CompatibilityVerdict.COMPATIBLE else CompatibilityVerdict.INCOMPATIBLE,
                    group?.reason ?: "This format matches.")
            } }, seed.output.proposal, null, groups, emptyList(), emptyList(), options.map { it.id })
        val run = AnalysisRun(workspace.id, "offline-test", analysisInputHash(options, wishes), mapper.writeValueAsString(output),
            createdAt = Instant.parse("2090-01-01T00:00:00Z"))
        transaction.executeWithoutResult { em.persist(run) }
        return Fixture(workspace.token, run, added)
    }

    private fun drafted(fixture: Fixture): MakerReplies {
        val view = replies.maker(fixture.token, fixture.run.id)
        return replies.generate(fixture.token, fixture.run.id, view.evidenceHash)
    }
    private fun approve(fixture: Fixture, group: MakerReplyGroup, body: String) {
        mvc.securePost("/w/${fixture.token}/maker/replies/approve") {
            param("run", fixture.run.id.toString()); param("group", group.evidence.groupId)
            param("evidenceHash", replies.maker(fixture.token, fixture.run.id).evidenceHash); param("body", body)
        }.andExpect { status { is3xxRedirection() } }
    }

    @TestConfiguration
    class Configuration {
        @Bean @Primary fun fakeReplyDrafter() = FakeReplyDrafter()
    }
    class FakeReplyDrafter : ReplyDrafter {
        var calls = 0
        var failure = false
        var fabricate = false
        override fun draft(groups: List<ReplyEvidenceGroup>): ReplyDraftResult {
            calls++
            if (failure) return ReplyDraftResult.Failure("PROVIDER_FAILURE")
            return ReplyDraftResult.Success(groups.associate { it.groupId to
                if (fabricate) "Invented unsupported reason." else ReplySafety.body(it.reasons.single()) }, "offline-test")
        }
    }
}

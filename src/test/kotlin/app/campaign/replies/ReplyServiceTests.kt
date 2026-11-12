package app.campaign.replies

import app.analysis.AnalysisProperties
import app.analysis.AnalysisTransport
import app.analysis.OpenAiAnalysisProvider
import app.analysis.ProviderHttpResponse
import app.analysis.replies.ValidatedReplyDrafter
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
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
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
    fun reset() { drafter.calls = 0; drafter.failure = false; drafter.fabricate = false; drafter.boundary = null }

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
    fun `free form original classifications produce bounded replies without copying explanations`() {
        val fixture = fixture(reasons = listOf("Requested wall display is outside the selected paper product.",
            "Requested glossy surface is absent from the matte catalog."))
        replyTransport()
        val view = replies.maker(fixture.token, fixture.run.id)
        assertTrue(view.groups.all { it.evidence.reasons == listOf(ReplySafety.notServedExplanation) })
        assertEquals(0, drafter.calls)
        val generated = replies.generate(fixture.token, fixture.run.id, view.evidenceHash)
        assertEquals("DRAFTED", generated.status)
        assertTrue(generated.groups.all { it.draft == ReplySafety.body(ReplySafety.notServedExplanation) })
        val group = generated.groups.first()
        assertNull(replies.approvedForWish(fixture.token, fixture.run.id, fixture.wishes.first()))
        approve(fixture, group, group.draft!!)
        val approved = assertNotNull(replies.approvedForWish(fixture.token, fixture.run.id, fixture.wishes.first()))
        assertEquals(group.evidence.sources.single { it.wishId == fixture.wishes.first() }.quote, approved.quote)
        assertEquals(group.draft, approved.body)
    }

    @Test
    fun `effective changes need explicit regeneration and fresh approval even for unchanged groups`() {
        val fixture = fixture()
        val original = drafted(fixture)
        val first = original.groups.first()
        approve(fixture, first, first.draft!!)
        val option = runs.find(fixture.token, fixture.run.id)!!.output.proposal!!.optionId
        reviews.decide(fixture.token, fixture.run.id, fixture.wishes.last(), option,
            CompatibilityVerdict.COMPATIBLE, "This format matches.")
        val refresh = replies.maker(fixture.token, fixture.run.id)
        assertEquals("REFRESH_REQUIRED", refresh.status)
        assertEquals(1, drafter.calls)
        assertEquals(first.evidence.groupId, refresh.groups.single().evidence.groupId)
        assertTrue(refresh.groups.all { it.draft == null && !it.approved })
        assertNull(replies.approvedForWish(fixture.token, fixture.run.id, fixture.wishes.first()))
        assertFailsWith<InvalidReplyEvidenceException> {
            replies.generate(fixture.token, fixture.run.id, original.evidenceHash)
        }
        val rebuilt = replies.generate(fixture.token, fixture.run.id, refresh.evidenceHash)
        assertEquals("DRAFTED", rebuilt.status)
        assertEquals(2, drafter.calls)
        assertTrue(rebuilt.groups.none { it.approved })
        approve(fixture, rebuilt.groups.single(), rebuilt.groups.single().draft!!)
        assertNotNull(replies.approvedForWish(fixture.token, fixture.run.id, fixture.wishes.first()))
    }

    @Test
    fun `older provider completion cannot overwrite a newer explicit refresh`() {
        val fixture = fixture()
        drafter.boundary = ReplyDrafter { groups ->
            drafter.boundary = null
            val option = runs.find(fixture.token, fixture.run.id)!!.output.proposal!!.optionId
            reviews.decide(fixture.token, fixture.run.id, fixture.wishes.last(), option,
                CompatibilityVerdict.COMPATIBLE, "This format matches.")
            val current = replies.maker(fixture.token, fixture.run.id)
            assertEquals("DRAFTED", replies.generate(fixture.token, fixture.run.id, current.evidenceHash).status)
            ReplyDraftResult.Success(groups.associate { it.groupId to ReplySafety.body(it.reasons.single()) }, "older-model")
        }
        val result = drafted(fixture)
        assertEquals("DRAFTED", result.status)
        assertEquals(1, result.groups.size)
        assertEquals(2, drafter.calls)
    }

    @Test
    fun `a draft interrupted by changed input can be refreshed when that input becomes valid again`() {
        val fixture = fixture()
        var added: UUID? = null
        drafter.boundary = ReplyDrafter { groups ->
            added = workspaces.addWish(fixture.token, "An additional request during drafting").id
            ReplyDraftResult.Success(groups.associate { it.groupId to ReplySafety.body(it.reasons.single()) }, "offline-test")
        }
        assertEquals("REANALYSIS_REQUIRED", drafted(fixture).status)
        workspaces.setWishHidden(fixture.token, added!!, true)
        drafter.boundary = null
        val refresh = replies.maker(fixture.token, fixture.run.id)
        assertEquals("REFRESH_REQUIRED", refresh.status)
        assertTrue(refresh.groups.all { it.draft == null && !it.approved })
        assertEquals("DRAFTED", replies.generate(fixture.token, fixture.run.id, refresh.evidenceHash).status)
        assertEquals(2, drafter.calls)
    }

    @Test
    fun `new input requires reanalysis and an old link can explicitly draft from the latest valid run`() {
        val fixture = fixture()
        val original = drafted(fixture)
        approve(fixture, original.groups.first(), original.groups.first().draft!!)
        val added = workspaces.addWish(fixture.token, "A new source after analysis")
        val stale = replies.maker(fixture.token, fixture.run.id)
        assertEquals("REANALYSIS_REQUIRED", stale.status)
        assertTrue(stale.groups.isEmpty())
        assertEquals("", stale.evidenceHash)
        assertEquals(1, drafter.calls)
        val stored = runs.find(fixture.token, fixture.run.id)!!
        val options = workspaces.listOptions(fixture.token)
        val output = stored.originalOutput.copy(
            wishes = stored.output.wishes + AnalyzedWish(added.id, WishKind.REQUEST, emptyList()),
            verdicts = stored.output.verdicts + options.map {
                WishVerdict(added.id, it.id, CompatibilityVerdict.COMPATIBLE, "This format matches.")
            },
        )
        val latest = AnalysisRun(fixture.run.workspaceId, "offline-test",
            analysisInputHash(options, workspaces.listWishes(fixture.token, false)), mapper.writeValueAsString(output),
            createdAt = Instant.parse("2091-01-01T00:00:00Z"))
        transaction.executeWithoutResult { em.persist(latest) }
        val refresh = replies.maker(fixture.token, fixture.run.id)
        assertEquals("REFRESH_REQUIRED", refresh.status)
        assertEquals(latest.id, refresh.runId)
        assertTrue(refresh.groups.isEmpty())
        assertEquals(1, drafter.calls)
        assertNull(replies.approvedForWish(fixture.token, fixture.run.id, fixture.wishes.first()))
        val generated = replies.generate(fixture.token, refresh.runId, refresh.evidenceHash)
        assertEquals("DRAFTED", generated.status)
        assertTrue(generated.groups.none { it.approved })
        assertEquals(2, drafter.calls)
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
        val stale = mvc.get(path) { param("run", fixture.run.id.toString()) }
            .andExpect { status { isOk() } }.andReturn().modelAndView!!.model["replies"] as MakerReplies
        assertEquals("REANALYSIS_REQUIRED", stale.status)
        assertTrue(stale.groups.isEmpty())

        val empty = fixture(empty = true)
        assertEquals("EMPTY", replies.maker(empty.token, empty.run.id).status)
        assertContains(mvc.get("/w/${empty.token}/maker/replies") { param("run", empty.run.id.toString()) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString, "No request groups need a reply")
    }

    @Test
    fun `maker groups identical reasons in collapsed sections while retaining every draft and approval action`() {
        val reason = "This batch uses a different format."
        val fixture = fixture(reasons = listOf(reason, reason))
        val generated = drafted(fixture)
        approve(fixture, generated.groups.first(), generated.groups.first().draft!!)
        val before = replies.maker(fixture.token, fixture.run.id)
        val calls = drafter.calls
        val result = mvc.get("/w/${fixture.token}/maker/replies") { param("run", fixture.run.id.toString()) }
            .andExpect { status { isOk() } }.andReturn()
        assertEquals(before.groups.groupBy { it.evidence.reasons }, result.modelAndView!!.model["replyReasonGroups"])
        val html = result.response.contentAsString
        val reasonSections = Regex("<details[^>]*data-reply-reason-group[^>]*>").findAll(html).toList()
        assertEquals(1, reasonSections.size)
        assertFalse(reasonSections.single().value.contains(" open"))
        assertContains(html, "2 reply groups")
        assertEquals(2, Regex("<details[^>]*data-reply-group\\s").findAll(html).count())
        for (group in before.groups) {
            val block = html.substringAfter("id=\"reply-group-${group.evidence.groupId}\"").substringBefore("</details>")
            assertContains(block, "Saved draft")
            assertEquals(group.approved, block.contains("Approved for these wishes."))
            assertContains(block, "name=\"run\" value=\"${before.runId}\"")
            assertContains(block, "name=\"group\" value=\"${group.evidence.groupId}\"")
            assertContains(block, "name=\"evidenceHash\" value=\"${before.evidenceHash}\"")
            assertContains(block, "id=\"reply-${group.evidence.groupId}\"")
            assertContains(block, group.draft!!)
            assertContains(block, "action=\"/w/${fixture.token}/maker/replies/approve\"")
        }
        assertContains(html, "&lt;script&gt;fanMarker()&lt;/script&gt;")
        assertFalse(html.contains("<script>fanMarker()"))
        assertEquals(before, replies.maker(fixture.token, fixture.run.id))
        assertEquals(calls, drafter.calls)
    }

    @Test
    fun `intake real reply transport maker approval and fan page show only the fans approved reply`() {
        val transport = replyTransport()
        val sessions = List(3) { MockHttpSession() }
        val fixture = fixture(sessions = sessions)
        val makerPath = "/w/${fixture.token}/maker/replies"
        val fanPath = "/w/${fixture.token}"
        val proposal = mvc.get("/w/${fixture.token}/maker/proposal") { param("run", fixture.run.id.toString()) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(proposal, "href=\"$makerPath?run=${fixture.run.id}\"")
        assertContains(proposal, "data-workbench-table")
        assertContains(proposal, "demand-explorer")
        val initial = replies.maker(fixture.token, fixture.run.id)
        mvc.securePost("$makerPath/generate") {
            param("run", fixture.run.id.toString()); param("evidenceHash", initial.evidenceHash)
        }.andExpect { status { is3xxRedirection() } }
        assertEquals(1, transport.size)
        val drafted = replies.maker(fixture.token, fixture.run.id)
        assertEquals("DRAFTED", drafted.status)
        for (fanSession in sessions) {
            val result = mvc.get(fanPath) { session = fanSession }.andExpect { status { isOk() } }.andReturn()
            assertFalse(result.response.contentAsString.contains("Maker-approved reply"))
            assertFalse(result.modelAndView!!.model.containsKey("replies"))
            assertNull(result.modelAndView!!.model["approvedReply"])
        }
        val group = drafted.groups.single { fixture.wishes.first() in it.evidence.sources.map(ReplySource::wishId) }
        approve(fixture, group, group.draft!!)
        for (index in 0..1) {
            val result = mvc.get(fanPath) { session = sessions[index] }.andExpect { status { isOk() } }.andReturn()
            val reply = result.modelAndView!!.model["approvedReply"] as ApprovedWishReply
            assertEquals(group.draft, reply.body)
            assertEquals(group.evidence.sources.single { it.wishId == fixture.wishes[index] }.quote, reply.quote)
            val block = result.response.contentAsString.substringAfter("aria-label=\"Approved maker reply\"").substringBefore("</aside>")
            assertContains(block, "Maker-approved reply")
            assertFalse(block.contains("Different group private phrase"))
            if (index == 0) {
                assertContains(block, "&lt;script&gt;fanMarker()&lt;/script&gt;")
                assertFalse(block.contains("<script>fanMarker()"))
                assertFalse(block.contains("Another fan private phrase"))
            }
            assertContains(result.response.contentAsString, "/questions?wish=${fixture.wishes[index]}")
        }
        for (fanSession in listOf(sessions.last(), MockHttpSession())) {
            assertFalse(mvc.get(fanPath) { session = fanSession }.andExpect { status { isOk() } }
                .andReturn().response.contentAsString.contains("Maker-approved reply"))
        }
        mvc.securePost("$makerPath/generate") {
            param("run", fixture.run.id.toString()); param("evidenceHash", initial.evidenceHash)
        }.andExpect { status { is3xxRedirection() } }
        assertEquals(1, transport.size)
        workspaces.setWishHidden(fixture.token, fixture.wishes.first(), true)
        assertFalse(mvc.get(fanPath) { session = sessions.first() }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString.contains("Maker-approved reply"))
    }

    @Test
    fun `exact subsequent batch commitment from effective override fails generation approval and fan reads`() {
        val transport = replyTransport()
        val sessions = List(3) { MockHttpSession() }
        val fixture = fixture(sessions = sessions)
        val unsupported = "Different format; we are going to make yours in a subsequent batch."
        val option = runs.find(fixture.token, fixture.run.id)!!.output.proposal!!.optionId
        reviews.decide(fixture.token, fixture.run.id, fixture.wishes.first(), option,
            CompatibilityVerdict.INCOMPATIBLE, unsupported)
        val view = replies.maker(fixture.token, fixture.run.id)
        val group = view.groups.single { fixture.wishes.first() in it.evidence.sources.map(ReplySource::wishId) }
        assertEquals(listOf(unsupported), group.evidence.reasons)
        assertFalse(ReplySafety.isSafeReason(unsupported))
        assertFalse(ReplySafety.isSafeReason("A harmless but unsupported paraphrase."))
        assertFailsWith<IllegalArgumentException> { ReplySafety.body(unsupported) }
        val unsafeBody = "Thank you for sharing your wish. $unsupported"
        assertFalse(ReplySafety.validateBody(unsafeBody, group.evidence.reasons))
        assertEquals("UNSAFE_REASON", replies.generate(fixture.token, fixture.run.id, view.evidenceHash).status)
        assertTrue(transport.isEmpty())
        transaction.executeWithoutResult {
            val batch = em.find(ReplyBatch::class.java, fixture.run.id)
            batch.status = "DRAFTED"
            batch.draftsJson = mapper.writeValueAsString(mapOf(group.evidence.groupId to unsafeBody))
        }
        assertFailsWith<UnsafeReplyException> {
            replies.approve(fixture.token, fixture.run.id, group.evidence.groupId, view.evidenceHash, unsafeBody)
        }
        transaction.executeWithoutResult {
            em.persist(ReplyApproval(UUID.nameUUIDFromBytes("${fixture.run.id}:${group.evidence.groupId}".toByteArray()),
                fixture.run.workspaceId, fixture.run.id, group.evidence.groupId, view.evidenceHash, unsafeBody, Instant.EPOCH))
        }
        assertNull(replies.approvedForWish(fixture.token, fixture.run.id, fixture.wishes.first()))
        mvc.get("/w/${fixture.token}/wishes/${fixture.wishes.first()}/reply") { param("run", fixture.run.id.toString()) }
            .andExpect { status { isNoContent() } }
        val page = mvc.get("/w/${fixture.token}") { session = sessions.first() }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertFalse(page.contains("Maker-approved reply"))
        assertFalse(page.contains(unsupported))
    }

    private data class Fixture(val token: String, val run: AnalysisRun, val wishes: List<UUID>)
    private fun fixture(
        empty: Boolean = false,
        sessions: List<MockHttpSession>? = null,
        reasons: List<String> = listOf("This batch uses a different format.", "This batch has a different finish."),
    ): Fixture {
        val workspace = workspaces.create()
        val added = listOf("I asked for a poster under $12 <script>fanMarker()</script>",
            "Another fan private phrase: a poster please", "Different group private phrase: glossy finish").mapIndexed { index, text ->
            if (sessions == null) workspaces.addWish(workspace.token, text).id
            else {
                mvc.securePost("/w/${workspace.token}", existingSession = sessions[index]) {
                    param("text", text)
                }.andExpect { status { is3xxRedirection() } }
                sessions[index].getAttribute("followup.wish.${workspace.token}") as UUID
            }
        }
        val seed = runs.latest(workspace.token)!!
        val wishes = workspaces.listWishes(workspace.token, false)
        val options = workspaces.listOptions(workspace.token)
        val option = seed.output.proposal!!.optionId
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

    private fun replyTransport(): MutableList<HttpRequest> {
        val requests = mutableListOf<HttpRequest>()
        val properties = AnalysisProperties("offline-test")
        val provider = OpenAiAnalysisProvider(properties, mapper, AnalysisTransport { request ->
            try {
                requests += request
                val bytes = java.io.ByteArrayOutputStream()
                request.bodyPublisher().orElseThrow().subscribe(object : Flow.Subscriber<ByteBuffer> {
                    override fun onSubscribe(subscription: Flow.Subscription) { subscription.request(Long.MAX_VALUE) }
                    override fun onNext(item: ByteBuffer) { bytes.write(ByteArray(item.remaining()).also(item::get)) }
                    override fun onError(throwable: Throwable) { throw throwable }
                    override fun onComplete() = Unit
                })
                val body = mapper.readTree(bytes.toString(Charsets.UTF_8))
                val groups = mapper.readTree(body["input"][0]["content"].asString())["groups"]
                val output = mapOf("replies" to groups.toList().map { group ->
                    mapOf("group_id" to group["group_id"].asString(),
                        "reason_index" to 0, "source_indices" to group["sources"].toList().indices.toList())
                })
                val response = mapper.writeValueAsString(mapOf("status" to "completed", "model" to "offline-model", "error" to null,
                    "output" to listOf(mapOf("type" to "message", "content" to listOf(mapOf("type" to "output_text",
                        "text" to mapper.writeValueAsString(output)))))))
                CompletableFuture.completedFuture(ProviderHttpResponse(200, response))
            } catch (exception: RuntimeException) {
                throw AssertionError("Invalid offline transport fixture", exception)
            }
        })
        drafter.boundary = ValidatedReplyDrafter(properties, provider, mapper)
        return requests
    }

    @TestConfiguration
    class Configuration {
        @Bean @Primary fun fakeReplyDrafter() = FakeReplyDrafter()
    }
    class FakeReplyDrafter : ReplyDrafter {
        var calls = 0
        var failure = false
        var fabricate = false
        var boundary: ReplyDrafter? = null
        override fun draft(groups: List<ReplyEvidenceGroup>): ReplyDraftResult {
            calls++
            boundary?.let { return it.draft(groups) }
            if (failure) return ReplyDraftResult.Failure("PROVIDER_FAILURE")
            return ReplyDraftResult.Success(groups.associate { it.groupId to
                if (fabricate) "Invented unsupported reason." else ReplySafety.body(it.reasons.single()) }, "offline-test")
        }
    }
}

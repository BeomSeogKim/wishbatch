package app.campaign.offerdraft

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.model.AnalysisRun
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Campaign
import app.campaign.model.CampaignState
import app.campaign.model.Offer
import app.campaign.model.Option
import app.campaign.model.WishKind
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalyzedWish
import app.campaign.port.Proposal
import app.campaign.port.ProposalEvidence
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineOfferGenerator : OfferDraftGenerator {
    val calls = mutableListOf<OfferDraftInput>()
    var result: OfferDraftGeneration = OfferDraftGeneration.Generated(
        """{"title":"Art print","description":"Soft paper. Pay two dollars."}""", "offline-model",
    )
    override fun generate(input: OfferDraftInput, timeout: Duration, maxOutputTokens: Int): OfferDraftGeneration {
        assertEquals(Duration.ofSeconds(15), timeout)
        assertEquals(1536, maxOutputTokens)
        assertTrue(input.evidence.size <= 10)
        calls += input
        return result
    }
}

@TestConfiguration
class OfflineOfferConfiguration {
    @Bean fun offlineOfferGenerator() = OfflineOfferGenerator()
}

@SpringBootTest
@AutoConfigureMockMvc
@Import(OfflineOfferConfiguration::class)
@Transactional
class OfferDraftServiceTests @Autowired constructor(
    private val drafts: OfferDraftService,
    private val workspaces: WorkspaceService,
    private val runs: AnalysisRunService,
    private val reviews: WishReviewService,
    private val mapper: JsonMapper,
    private val entityManager: EntityManager,
    private val generator: OfflineOfferGenerator,
    private val mvc: MockMvc,
) {
    data class Fixture(val token: String, val run: AnalysisRun, val option: Option, val wishId: UUID)

    @BeforeEach
    fun reset() {
        generator.calls.clear()
        generator.result = OfferDraftGeneration.Generated(
            """{"title":"Art print","description":"Soft paper. Pay two dollars."}""", "offline-model",
        )
    }

    @Test
    fun `generation persists safe copy removals and exact separate evidence then explicit review exposes only prose`() {
        val fixture = fixture()
        val before = drafts.page(fixture.token, fixture.run.id)
        assertNull(before.draft)
        assertEquals(0, generator.calls.size)
        assertIs<OfferDraftGeneration.Generated>(drafts.generate(fixture.token, fixture.run.id, before.sourceHash))
        entityManager.flush()
        entityManager.clear()
        val page = drafts.page(fixture.token, fixture.run.id)
        val draft = assertNotNull(page.draft)
        assertEquals("Soft paper.", draft.generatedDescription)
        assertEquals("Pay two dollars.", page.removals.single().text)
        assertEquals("I like <script>alert('wish')</script>.", page.evidence.single().quote)
        assertContains(draft.evidenceJson, fixture.wishId.toString())
        assertFailsWith<InvalidOfferDraftException> {
            drafts.reviewedCopy(fixture.token, fixture.run.id, draft.id, page.sourceHash)
        }
        val title = "<script>alert('title')</script>"
        drafts.save(fixture.token, fixture.run.id, draft.id, page.sourceHash, title, "Maker edited description")
        entityManager.flush()
        entityManager.clear()
        assertEquals(mapOf("title" to title, "specText" to "Maker edited description"),
            drafts.reviewedCopy(fixture.token, fixture.run.id, draft.id, page.sourceHash))
        assertEquals("Art print", drafts.page(fixture.token, fixture.run.id).draft!!.generatedTitle)
        assertEquals(1, generator.calls.size)
    }

    @Test
    fun `get escapes both sources and edits without generating and posts require csrf`() {
        val f = fixture()
        val page = drafts.page(f.token, f.run.id)
        mvc.get("/w/${f.token}/maker/offer-draft") { param("run", f.run.id.toString()) }.andExpect { status { isOk() } }
        assertTrue(generator.calls.isEmpty())
        mvc.post("/w/${f.token}/maker/offer-draft/generate") {
            param("run", f.run.id.toString()); param("sourceHash", page.sourceHash)
        }.andExpect { status { isForbidden() } }
        assertTrue(generator.calls.isEmpty())
        mvc.securePost("/w/${f.token}/maker/offer-draft/generate") {
            param("run", f.run.id.toString()); param("sourceHash", page.sourceHash)
        }.andExpect { status { is3xxRedirection() } }
        val draft = drafts.page(f.token, f.run.id).draft!!
        mvc.post("/w/${f.token}/maker/offer-draft/save") {
            param("run", f.run.id.toString()); param("draft", draft.id.toString()); param("sourceHash", page.sourceHash)
            param("title", "<script>bad()</script>"); param("description", "<img src=x onerror=bad()>")
        }.andExpect { status { isForbidden() } }
        mvc.securePost("/w/${f.token}/maker/offer-draft/save") {
            param("run", f.run.id.toString()); param("draft", draft.id.toString()); param("sourceHash", page.sourceHash)
            param("title", "<script>bad()</script>"); param("description", "<img src=x onerror=bad()>")
        }.andExpect { status { is3xxRedirection() } }
        val html = mvc.get("/w/${f.token}/maker/offer-draft") {
            param("run", f.run.id.toString())
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(html, "Reviewed draft")
        assertContains(html, "&lt;script&gt;bad()&lt;/script&gt;")
        assertContains(html, "&lt;img src=x onerror=bad()&gt;")
        assertContains(html, "&lt;script&gt;alert(&#39;wish&#39;)&lt;/script&gt;")
        assertContains(html, "name=\"_csrf\"")
        assertFalse(html.contains("<script>bad()"))
        assertEquals(1, generator.calls.size)
        assertEquals(0L, entityManager.createQuery("select count(o) from Offer o where o.workspaceId = :id", Long::class.javaObjectType)
            .setParameter("id", f.run.workspaceId).singleResult)
    }

    @Test
    fun `two failed attempts exhaust the run without retry or a third call`() {
        val f = fixture()
        val hash = drafts.page(f.token, f.run.id).sourceHash
        generator.result = OfferDraftGeneration.Generated("""{"title":"Two prints","description":"Delivery tomorrow."}""", "offline")
        repeat(2) { assertIs<OfferDraftGeneration.Unavailable>(drafts.generate(f.token, f.run.id, hash)) }
        assertEquals(OfferDraftGeneration.Unavailable("ATTEMPT_LIMIT"), drafts.generate(f.token, f.run.id, hash))
        assertEquals(2, generator.calls.size)
        assertFalse(drafts.page(f.token, f.run.id).canGenerate)
        assertNull(drafts.page(f.token, f.run.id).draft!!.generatedTitle)
    }

    @Test
    fun `failed regeneration preserves reviewed text and source changes do not reset attempt quota`() {
        val f = fixture()
        val hash = drafts.page(f.token, f.run.id).sourceHash
        drafts.generate(f.token, f.run.id, hash)
        val draft = drafts.page(f.token, f.run.id).draft!!
        drafts.save(f.token, f.run.id, draft.id, hash, "Keep reviewed title", "Keep reviewed description")
        generator.result = OfferDraftGeneration.Unavailable("TIMEOUT")
        drafts.generate(f.token, f.run.id, hash)
        val page = drafts.page(f.token, f.run.id)
        assertTrue(page.generationFailed)
        assertEquals(draft.id, page.draft!!.id)
        assertEquals("Keep reviewed title", drafts.reviewedCopy(f.token, f.run.id, draft.id, hash)["title"])
        reviews.decide(f.token, f.run.id, f.wishId, f.option.id, CompatibilityVerdict.COMPATIBLE, "Updated review")
        val changed = drafts.page(f.token, f.run.id)
        assertNull(changed.draft)
        assertEquals(OfferDraftGeneration.Unavailable("ATTEMPT_LIMIT"), drafts.generate(f.token, f.run.id, changed.sourceHash))
        assertEquals(2, generator.calls.size)
    }

    @Test
    fun `explicit use copies only title and specification while retaining typed terms and requiring later publication confirmation`() {
        val f = fixture()
        val page = drafts.page(f.token, f.run.id)
        drafts.generate(f.token, f.run.id, page.sourceHash)
        val draft = drafts.page(f.token, f.run.id).draft!!
        drafts.save(f.token, f.run.id, draft.id, page.sourceHash, "Reviewed title", "Reviewed description")
        val fields = mapOf("run" to f.run.id.toString(), "draft" to draft.id.toString(), "draftSourceHash" to page.sourceHash,
            "title" to "Typed title", "specText" to "Typed spec", "unitPrice" to "975", "deliveryTerms" to "Typed delivery terms",
            "minBatch" to "4", "capacity" to "8", "maxQtyPerPledge" to "2", "duration" to "120",
            "variant.finish" to "matte", "question.0" to "dismissed", "action" to "use-reviewed-draft")
        mvc.post("/w/${f.token}/maker/offer") { fields.forEach { (key, value) -> param(key, value) } }
            .andExpect { status { isForbidden() } }
        mvc.get("/w/${f.token}/maker/offer") { param("run", f.run.id.toString()) }.andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString.let { html ->
            assertContains(html, "Use reviewed draft")
            assertContains(html, "/maker/offer-draft?run=${f.run.id}")
        }
        val response = mvc.securePost("/w/${f.token}/maker/offer") {
            fields.forEach { (key, value) -> param(key, value) }
        }.andExpect {
            status { is3xxRedirection() }; redirectedUrl("/w/${f.token}/maker/offer?run=${f.run.id}")
        }.andReturn()
        @Suppress("UNCHECKED_CAST")
        val copied = response.flashMap["fields"] as Map<String, String>
        assertEquals(fields + mapOf("title" to "Reviewed title", "specText" to "Reviewed description", "reviewedDraftApplied" to "true"), copied)
        val html = mvc.get("/w/${f.token}/maker/offer") {
            param("run", f.run.id.toString()); flashAttr("fields", copied)
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(html, "Reviewed draft: check the title and specification")
        assertContains(html, "Typed delivery terms")
        assertContains(html, "value=\"975\"")
        assertEquals(0L, entityManager.createQuery("select count(o) from Offer o where o.workspaceId = :id", Long::class.javaObjectType)
            .setParameter("id", f.run.workspaceId).singleResult)
        mvc.securePost("/w/${f.token}/maker/offer") {
            copied.filterKeys { it != "action" }.forEach { (key, value) -> param(key, value) }
        }.andExpect { flash { attributeExists("errors") } }
        assertEquals(1, generator.calls.size)
    }

    @Test
    fun `foreign workspace cannot read save or copy a draft`() {
        val f = fixture()
        val other = fixture()
        val page = drafts.page(f.token, f.run.id)
        drafts.generate(f.token, f.run.id, page.sourceHash)
        val id = drafts.page(f.token, f.run.id).draft!!.id
        assertFailsWith<WorkspaceNotFoundException> { drafts.page(other.token, f.run.id) }
        val otherPage = drafts.page(other.token, other.run.id)
        assertFailsWith<InvalidOfferDraftException> {
            drafts.save(other.token, other.run.id, id, otherPage.sourceHash, "Title", "Description")
        }
    }

    @Test
    fun `hidden or changed wishes and changed options invalidate source before provider call`() {
        val f = fixture()
        val hash = drafts.page(f.token, f.run.id).sourceHash
        workspaces.setWishHidden(f.token, f.wishId, true)
        assertFailsWith<InvalidOfferDraftException> { drafts.generate(f.token, f.run.id, hash) }
        assertTrue(generator.calls.isEmpty())
    }

    @Test
    fun `changed source text and option attributes invalidate the analysis snapshot`() {
        val f = fixture()
        entityManager.createNativeQuery("update wishes set text = :text where id = :id")
            .setParameter("text", "Changed wish").setParameter("id", f.wishId).executeUpdate()
        entityManager.clear()
        assertFailsWith<InvalidOfferDraftException> { drafts.page(f.token, f.run.id) }
        val other = fixture()
        entityManager.createNativeQuery("update product_options set name = :name where id = :id")
            .setParameter("name", "Changed option").setParameter("id", other.option.id).executeUpdate()
        entityManager.clear()
        assertFailsWith<InvalidOfferDraftException> { drafts.page(other.token, other.run.id) }
    }

    @Test
    fun `a changed override makes saved copy stale even if the source wish is unchanged`() {
        val f = fixture()
        val page = drafts.page(f.token, f.run.id)
        drafts.generate(f.token, f.run.id, page.sourceHash)
        val draft = drafts.page(f.token, f.run.id).draft!!
        drafts.save(f.token, f.run.id, draft.id, page.sourceHash, "Reviewed", "Maker description")
        reviews.decide(f.token, f.run.id, f.wishId, f.option.id, CompatibilityVerdict.COMPATIBLE, "Maker verified this format")
        assertFailsWith<InvalidOfferDraftException> { drafts.reviewedCopy(f.token, f.run.id, draft.id, page.sourceHash) }
        assertTrue(drafts.page(f.token, f.run.id).sourceHash != page.sourceHash)
    }

    @Test
    fun `no proposal invented quote and foreign evidence never reach generator`() {
        for (mode in listOf("no-proposal", "invented-quote", "foreign-wish")) {
            val f = fixture(mode)
            assertFailsWith<InvalidOfferDraftException> { drafts.page(f.token, f.run.id) }
        }
        assertTrue(generator.calls.isEmpty())
    }

    @Test
    fun `published offer blocks draft generation edits and copy and preserves exact terms`() {
        val f = fixture()
        val page = drafts.page(f.token, f.run.id)
        drafts.generate(f.token, f.run.id, page.sourceHash)
        val draft = drafts.page(f.token, f.run.id).draft!!
        drafts.save(f.token, f.run.id, draft.id, page.sourceHash, "Reviewed", "Maker description")
        val offer = Offer(f.run.workspaceId, f.option.id, f.run.id, "Exact title", "Exact specification", emptyMap(),
            700, "USD", "Maker delivery terms", "US", 2, 10, 3, listOf(f.wishId))
        entityManager.persist(offer)
        val openedAt = Instant.parse("2026-11-12T01:00:00Z")
        entityManager.persist(Campaign(offer.id, CampaignState.OPEN, openedAt = openedAt,
            closesAt = openedAt.plusSeconds(7200), confirmDeadline = openedAt.plusSeconds(50400)))
        assertFalse(drafts.page(f.token, f.run.id).canGenerate)
        assertFailsWith<InvalidOfferDraftException> { drafts.generate(f.token, f.run.id, page.sourceHash) }
        assertFailsWith<InvalidOfferDraftException> { drafts.save(f.token, f.run.id, draft.id, page.sourceHash, "Changed", "Changed") }
        assertFailsWith<InvalidOfferDraftException> { drafts.reviewedCopy(f.token, f.run.id, draft.id, page.sourceHash) }
        mvc.securePost("/w/${f.token}/maker/offer") {
            param("run", f.run.id.toString()); param("draft", draft.id.toString())
            param("draftSourceHash", page.sourceHash); param("action", "use-reviewed-draft")
        }.andExpect { flash { attributeExists("errors") } }
        assertEquals("Exact title", offer.title)
        assertEquals(700L, offer.unitPrice)
        assertEquals("Maker delivery terms", offer.deliveryTerms)
        assertEquals(1, generator.calls.size)
    }

    private fun fixture(mode: String = "valid"): Fixture {
        val workspace = workspaces.create()
        val option = Option(workspace.id, "Art print", "print", mapOf("paper" to "Soft paper"), emptyMap(),
            500, "USD", 2, 10, "Maker confirmed delivery", "US")
        entityManager.persist(option)
        val wish = workspaces.addWish(workspace.token, "I like <script>alert('wish')</script>.")
        val evidenceId = if (mode == "foreign-wish") UUID.randomUUID() else wish.id
        val output = AnalysisOutput(listOf(AnalyzedWish(wish.id, WishKind.REQUEST, emptyList())),
            listOf(WishVerdict(wish.id, option.id, CompatibilityVerdict.COMPATIBLE, "Supported format")),
            if (mode == "no-proposal") null else Proposal(option.id, emptyMap(), listOf(ProposalEvidence(
                "Supported format", evidenceId, if (mode == "invented-quote") "Invented quote" else wish.text))),
            if (mode == "no-proposal") "No feasible option" else null, emptyList(), emptyList(), emptyList(), listOf(option.id))
        val run = AnalysisRun(workspace.id, "offline", analysisInputHash(workspaces.listOptions(workspace.token),
            workspaces.listWishes(workspace.token, includeHidden = false)), mapper.writeValueAsString(output),
            createdAt = Instant.parse("2026-11-12T01:00:00Z"))
        entityManager.persist(run)
        entityManager.flush()
        assertNotNull(runs.find(workspace.token, run.id))
        return Fixture(workspace.token, run, option, wish.id)
    }
}

package app.campaign.gloss

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.model.AnalysisRun
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.NeedAttribute
import app.campaign.model.NeedStrength
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalyzedWish
import app.campaign.port.WishNeed
import app.campaign.port.WishVerdict
import app.campaign.provenance.DecisionRecordService
import app.campaign.rules.optionWishCounts
import app.campaign.workbench.wishWorkbenchRows
import app.campaign.workspace.WorkspaceNotFoundException
import app.campaign.workspace.WorkspaceService
import app.web.security.securePost
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.mock.env.MockEnvironment
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(properties = ["LLM_DAILY_RUN_CAP=100"])
@AutoConfigureMockMvc
@Import(WishGlossServiceTests.TranslatorConfiguration::class)
@Transactional
class WishGlossServiceTests @Autowired constructor(
    private val glosses: WishGlossService,
    private val workspaces: WorkspaceService,
    private val runs: AnalysisRunService,
    private val records: DecisionRecordService,
    private val mapper: JsonMapper,
    private val entities: EntityManager,
    private val mvc: MockMvc,
    private val translator: OfflineTranslator,
    private val translators: ObjectProvider<WishGlossTranslator>,
    private val clock: Clock,
) {
    class OfflineTranslator : WishGlossTranslator {
        val requests = mutableListOf<GlossRequest>()
        var result: ((GlossRequest) -> GlossResult)? = null
        override fun translate(request: GlossRequest): GlossResult {
            requests += request
            return result?.invoke(request) ?: GlossResult.Completed("offline-model", request.sources.map {
                GlossTranslation(it.wishId, it.sourceHash, GlossStatus.TRANSLATED,
                    if (it.text.contains("\ubb34\uad11")) "ko" else "ja", "Please make 2 matte prints. <b>Only matte.</b>")
            })
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TranslatorConfiguration {
        @Bean @Primary fun offlineTranslator() = OfflineTranslator()
    }

    @BeforeEach
    fun reset() {
        translator.requests.clear()
        translator.result = null
    }

    @Test
    fun `Korean and Japanese glosses persist without changing source quotes verdicts counts or provenance`() {
        val fixture = fixture()
        val before = assertNotNull(runs.find(fixture.token, fixture.run.id))
        val sourcePayload = snapshotPayload(fixture.run.id)
        assertTrue(glosses.generate(fixture.token, fixture.run.id, fixture.ids) is GlossResult.Completed)
        entities.flush()
        entities.clear()
        val rows = glosses.read(fixture.token, fixture.run.id).rows
        assertEquals(fixture.texts.toSet(), rows.map { it.original }.toSet())
        rows.forEach { row ->
            val saved = assertNotNull(row.saved)
            assertEquals("Machine translation", saved.label)
            assertEquals("offline-model", saved.model)
            assertNotNull(saved.generatedAt)
            assertEquals(1, saved.attempts)
            assertFalse(row.selectable)
        }
        val after = assertNotNull(runs.find(fixture.token, fixture.run.id))
        assertEquals(before.originalOutput, after.originalOutput)
        assertEquals(before.run.resultJson, after.run.resultJson)
        assertEquals(optionWishCounts(before.output), optionWishCounts(after.output))
        assertEquals(sourcePayload, snapshotPayload(fixture.run.id))
        val workbench = wishWorkbenchRows(workspaces.listWishes(fixture.token), workspaces.listOptions(fixture.token), after.output, after.flags)
        fixture.ids.forEach { id ->
            assertEquals(listOf("2"), workbench.single { it.id == id }.evidence)
            assertTrue(workbench.single { it.id == id }.text.contains("2"))
        }
        assertEquals(GlossResult.Failed(GlossFailure.LIMIT_REACHED), glosses.generate(fixture.token, fixture.run.id, fixture.ids))
        assertEquals(1, translator.requests.size)
    }

    @Test
    fun `stale hidden foreign and wrong run selections never reach translator`() {
        val fixture = fixture()
        val foreign = fixture()
        assertFailsWith<IllegalArgumentException> { glosses.generate(fixture.token, fixture.run.id, listOf(foreign.ids.first())) }
        assertFailsWith<WorkspaceNotFoundException> { glosses.generate(fixture.token, foreign.run.id, fixture.ids) }
        assertFailsWith<WorkspaceNotFoundException> { glosses.read("bad-token", fixture.run.id) }
        assertFailsWith<WorkspaceNotFoundException> { glosses.read(fixture.token, UUID.randomUUID()) }
        assertFailsWith<IllegalArgumentException> { glosses.generate(fixture.token, fixture.run.id, emptyList()) }
        assertFailsWith<IllegalArgumentException> { glosses.generate(fixture.token, fixture.run.id, List(21) { fixture.ids.first() }) }
        val added = workspaces.addWish(fixture.token, "New wish outside this run")
        assertFailsWith<IllegalArgumentException> { glosses.generate(fixture.token, fixture.run.id, listOf(added.id)) }
        glosses.generate(fixture.token, fixture.run.id, fixture.ids)
        translator.requests.clear()
        workspaces.setWishHidden(fixture.token, fixture.ids.first(), true)
        assertFalse(glosses.read(fixture.token, fixture.run.id).rows.any { it.wishId == fixture.ids.first() })
        assertFailsWith<IllegalArgumentException> { glosses.generate(fixture.token, fixture.run.id, listOf(fixture.ids.first())) }
        entities.createNativeQuery("update wishes set text = 'Changed original' where id = :id")
            .setParameter("id", fixture.ids.last()).executeUpdate()
        entities.clear()
        val row = glosses.read(fixture.token, fixture.run.id).rows.single()
        assertEquals("Changed original", row.original)
        assertNull(row.saved)
        assertFalse(row.selectable)
        assertFailsWith<IllegalArgumentException> { glosses.generate(fixture.token, fixture.run.id, listOf(row.wishId)) }
        assertTrue(translator.requests.isEmpty())
    }

    @Test
    fun `failed and invalid output consumes at most two attempts and preserves originals`() {
        val fixture = fixture()
        translator.result = { GlossResult.Failed(GlossFailure.TIMEOUT) }
        assertEquals(GlossResult.Failed(GlossFailure.TIMEOUT), glosses.generate(fixture.token, fixture.run.id, fixture.ids))
        translator.result = { request -> GlossResult.Completed("offline-model", request.sources.map {
            GlossTranslation(UUID.randomUUID(), it.sourceHash, GlossStatus.TRANSLATED, "ko", "Invented")
        }) }
        assertEquals(GlossResult.Failed(GlossFailure.INVALID_OUTPUT), glosses.generate(fixture.token, fixture.run.id, fixture.ids))
        entities.flush()
        entities.clear()
        assertEquals(GlossResult.Failed(GlossFailure.LIMIT_REACHED), glosses.generate(fixture.token, fixture.run.id, fixture.ids))
        assertEquals(2, translator.requests.size)
        glosses.read(fixture.token, fixture.run.id).rows.forEach {
            assertTrue(it.original in fixture.texts)
            assertEquals(2, it.saved?.attempts)
            assertNull(it.saved?.englishGloss)
        }
    }

    @Test
    fun `provider exceptions and oversized glosses are unavailable without partial saves`() {
        val fixture = fixture()
        translator.result = { throw IllegalStateException("Synthetic failure") }
        assertEquals(GlossResult.Failed(GlossFailure.PROVIDER_FAILURE), glosses.generate(fixture.token, fixture.run.id, fixture.ids))
        translator.result = { request -> GlossResult.Completed("offline-model", request.sources.map {
            GlossTranslation(it.wishId, it.sourceHash, GlossStatus.TRANSLATED, "ko", "x".repeat(1501))
        }) }
        assertEquals(GlossResult.Failed(GlossFailure.INVALID_OUTPUT), glosses.generate(fixture.token, fixture.run.id, fixture.ids))
        assertTrue(glosses.read(fixture.token, fixture.run.id).rows.all { it.saved?.status == null })
    }

    @Test
    fun `cap zero blocks even an injected translator without consuming attempts`() {
        val fixture = fixture()
        val disabled = WishGlossService(entities, workspaces, runs, mapper, translators,
            MockEnvironment().withProperty("LLM_DAILY_RUN_CAP", "0"), clock)
        assertEquals(GlossResult.Failed(GlossFailure.LIMIT_REACHED), disabled.generate(fixture.token, fixture.run.id, fixture.ids))
        assertTrue(disabled.read(fixture.token, fixture.run.id).rows.all { it.saved == null })
        assertTrue(translator.requests.isEmpty())
    }

    @Test
    fun `English and uncertain readings show no fabricated translation and cannot be generated again`() {
        val fixture = fixture()
        translator.result = { request -> GlossResult.Completed("offline-model", request.sources.mapIndexed { index, source ->
            GlossTranslation(source.wishId, source.sourceHash,
                if (index == 0) GlossStatus.ENGLISH else GlossStatus.UNCERTAIN,
                if (index == 0) "en" else null, null)
        }) }
        assertTrue(glosses.generate(fixture.token, fixture.run.id, fixture.ids) is GlossResult.Completed)
        assertTrue(glosses.read(fixture.token, fixture.run.id).rows.all { !it.selectable && it.saved?.englishGloss == null })
        val html = mvc.get("/w/${fixture.token}/maker/glosses") { param("run", fixture.run.id.toString()) }
            .andReturn().response.contentAsString
        assertContains(html, "Machine-inferred English")
        assertContains(html, "Language uncertain")
        assertEquals(GlossResult.Failed(GlossFailure.LIMIT_REACHED), glosses.generate(fixture.token, fixture.run.id, fixture.ids))
        assertEquals(1, translator.requests.size)
    }

    @Test
    fun `missing source snapshots and empty visible rosters remain unavailable without calls`() {
        val fixture = fixture()
        entities.createNativeQuery("delete from decision_source_snapshots where source_id = :run")
            .setParameter("run", fixture.run.id).executeUpdate()
        entities.clear()
        val page = glosses.read(fixture.token, fixture.run.id)
        assertEquals(fixture.texts.toSet(), page.rows.map { it.original }.toSet())
        assertTrue(page.rows.all { !it.current && !it.selectable })
        assertFailsWith<IllegalArgumentException> { glosses.generate(fixture.token, fixture.run.id, fixture.ids) }
        fixture.ids.forEach { workspaces.setWishHidden(fixture.token, it, true) }
        assertTrue(glosses.read(fixture.token, fixture.run.id).rows.isEmpty())
        val html = mvc.get("/w/${fixture.token}/maker/glosses") { param("run", fixture.run.id.toString()) }
            .andReturn().response.contentAsString
        assertContains(html, "No visible wishes in this analysis")
        assertTrue(translator.requests.isEmpty())
    }

    @Test
    fun `GET never translates and POST requires csrf while escaping both texts`() {
        val fixture = fixture()
        val path = "/w/${fixture.token}/maker/glosses"
        val initial = mvc.get(path) { param("run", fixture.run.id.toString()) }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertContains(initial, "No translated reading aids yet")
        assertContains(initial, "Unchecked wishes")
        assertContains(initial, "&lt;script&gt;")
        assertContains(initial, "name=\"_csrf\"")
        assertTrue(translator.requests.isEmpty())
        mvc.post(path) { param("run", fixture.run.id.toString()); param("wishIds", fixture.ids.first().toString()) }
            .andExpect { status { isForbidden() } }
        assertTrue(translator.requests.isEmpty())
        mvc.securePost(path) {
            param("run", fixture.run.id.toString()); param("wishIds", *fixture.ids.map { it.toString() }.toTypedArray())
        }.andExpect { status { is3xxRedirection() }; redirectedUrl("$path?run=${fixture.run.id}") }
        val html = mvc.get(path) { param("run", fixture.run.id.toString()) }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertContains(html, "Machine translation")
        assertContains(html, "&lt;b&gt;Only matte.&lt;/b&gt;")
        assertContains(html, "&lt;script&gt;")
        assertFalse(html.contains("<script>publish()</script>"))
        assertFalse(html.contains("<b>Only matte.</b>"))
        assertEquals(1, translator.requests.size)
    }

    @Test
    fun `failed POST retains original and reports unavailable on subsequent GET`() {
        val fixture = fixture()
        translator.result = { GlossResult.Failed(GlossFailure.NOT_CONFIGURED) }
        val path = "/w/${fixture.token}/maker/glosses"
        mvc.securePost(path) {
            param("run", fixture.run.id.toString()); param("wishIds", fixture.ids.first().toString())
        }.andExpect { status { is3xxRedirection() }; flash { attribute("glossNotice", "Translation is unavailable. Original wishes are unchanged.") } }
        val html = mvc.get(path) { param("run", fixture.run.id.toString()) }.andReturn().response.contentAsString
        assertContains(html, "\ubb34\uad11 2\uc7a5")
        assertContains(html, "No translated reading aids yet")
        assertContains(html, "Unchecked wishes")
        mvc.securePost(path) { param("run", fixture.run.id.toString()) }.andExpect { status { isBadRequest() } }
        mvc.get(path) { param("run", UUID.randomUUID().toString()) }.andExpect { status { isNotFound() } }
    }

    private data class Fixture(val token: String, val run: AnalysisRun, val wishes: List<Wish>) {
        val ids get() = wishes.map { it.id }
        val texts get() = wishes.map { it.text }
    }

    private fun fixture(): Fixture {
        val workspace = workspaces.create()
        val wishes = listOf("\ubb34\uad11 2\uc7a5 <script>publish()</script>", "\u30de\u30c3\u30c8\u30922\u679a\u304a\u9858\u3044\u3057\u307e\u3059").map {
            workspaces.addWish(workspace.token, it)
        }
        val option = workspaces.listOptions(workspace.token).first()
        val output = AnalysisOutput(
            wishes.map { AnalyzedWish(it.id, WishKind.REQUEST, listOf(WishNeed(NeedStrength.HARD, NeedAttribute.FINISH, "matte", "2"))) },
            wishes.map { WishVerdict(it.id, option.id, CompatibilityVerdict.CONDITIONAL, "Confirm finish.") },
            null, "Review needed.", emptyList(), emptyList(), emptyList(), listOf(option.id),
        )
        val run = AnalysisRun(workspace.id, "offline-model", "synthetic-hash", mapper.writeValueAsString(output),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"))
        entities.persist(run)
        records.recordAnalysis(run, wishes)
        return Fixture(workspace.token, run, wishes)
    }

    private fun snapshotPayload(runId: UUID): String = entities.createQuery(
        "select s.payload from DecisionSourceSnapshot s where s.sourceType = 'ANALYSIS' and s.sourceId = :run", String::class.java,
    ).setParameter("run", runId).singleResult
}

package app.campaign.reading

import app.campaign.model.*
import app.campaign.port.*
import app.campaign.provenance.*
import app.campaign.rules.analysisInputHash
import app.campaign.service.*
import app.campaign.store.*
import app.web.security.securePost
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@SpringBootTest
@AutoConfigureMockMvc
@Import(CampaignServiceTests.PaymentTestConfiguration::class, OfferWishReadingTests.Configuration::class)
class OfferWishReadingTests @Autowired constructor(
    private val readings: OfferWishReadingService,
    private val service: CampaignService,
    private val records: DecisionRecordService,
    private val queries: DecisionRecordQuery,
    private val provenance: PledgeProvenanceCheckpoint,
    private val entities: EntityManager,
    private val persistence: CampaignPersistence,
    private val pledges: PledgeRepository,
    private val gateway: CrashableGateway,
    private val analyzer: ReadingAnalyzer,
    private val clock: MutableClock,
    private val mapper: JsonMapper,
    private val mvc: MockMvc,
) {
    @MockitoBean private lateinit var tick: CampaignReconciliationTick
    private val owned = mutableListOf<UUID>()
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @BeforeEach fun reset() {
        gateway.reset()
        analyzer.calls.set(0)
        analyzer.fail = false
        analyzer.entered = null
        analyzer.release = null
        clock.current = now
    }

    @AfterEach fun cleanup() {
        owned.forEach { id -> persistence.checkpoint {
            val ids = "select c.id from Campaign c where c.offerId in (select o.id from Offer o where o.workspaceId = :id)"
            listOf(
                "DecisionRecordEntry e where e.pledgeId in (select r.pledgeId from PledgeDecisionRecord r where r.workspaceId = :id)",
                "PledgeDecisionRecord r where r.workspaceId = :id", "DecisionSourceSnapshot s where s.workspaceId = :id",
                "OfferWishReading r where r.workspaceId = :id", "Event e where e.campaignId in ($ids)",
                "Pledge p where p.campaignId in ($ids)", "Campaign c where c.id in ($ids)",
                "Offer o where o.workspaceId = :id", "AnalysisRun r where r.workspaceId = :id",
                "Option o where o.workspaceId = :id", "Wish w where w.workspaceId = :id", "Workspace w where w.id = :id",
            ).forEach { entities.createQuery("delete from $it").setParameter("id", id).executeUpdate() }
        } }
    }

    @Test fun `saved assessment is reused by pledge POST with exact evidence and no fan identity`() {
        val initial = fixture()
        val submitted = mvc.securePost("/w/${initial.workspace.token}") {
            param("text", "Exact postcard wish")
        }.andExpect { status { is3xxRedirection() }; flash { attributeExists("wishMatchNote") } }.andReturn()
        val wishId = submitted.flashMap["submittedWishId"] as UUID
        val f = initial.copy(wish = persistence.checkpoint { entities.find(Wish::class.java, wishId) })
        val saved = assertNotNull(readings.assessOpen(f.workspace.token, f.wish.id))
        val pledgeId = pledgePost(f)
        val record = record(f, pledgeId)
        assertEquals(1, analyzer.calls.get())
        assertEquals("INTACT", record.integrity)
        val reading = record.entries.single { it.kind == "READING" }.payload
        assertEquals(saved, (reading.deepCopy() as tools.jackson.databind.node.ObjectNode).also { it.remove("assessmentHash") })
        assertEquals("Exact postcard wish", reading["quote"].asString())
        assertEquals("synthetic-reading-model", reading["model"].asString())
        assertEquals(ReadingAnalyzer.completedAt.toString(), reading["completedAt"].asString())
        assertEquals(64, reading["inputHash"].asString().length)
        assertEquals(analysisInputHash(listOf(f.option), listOf(f.wish)), reading["input"]["classifierInputHash"].asString())
        assertFalse(mapper.writeValueAsString(queries.findByToken(f.workspace.token)).contains("Private Fan Name"))
        val html = mvc.get("/w/${f.workspace.token}/pledges/$pledgeId/trace").andReturn().response.contentAsString
        assertContains(html, "Exact postcard wish")
        assertContains(html, "synthetic-reading-model")
        assertContains(html, ReadingAnalyzer.completedAt.toString())
        assertContains(html, reading["inputHash"].asString())
        assertEquals(1, analyzer.calls.get())
    }

    @Test fun `missing reading uses pledge POST fallback once and GET stays read only`() {
        val f = fixture()
        val id = pledgePost(f)
        assertEquals(1, analyzer.calls.get())
        val before = record(f, id)
        repeat(3) { mvc.get("/w/${f.workspace.token}/pledges/$id/trace").andExpect { status { isOk() } } }
        assertEquals(before, record(f, id))
        assertEquals(1, analyzer.calls.get())
    }

    @Test fun `failure is pending and later success appends once without changing original verdict bytes`() {
        val f = fixture()
        analyzer.fail = true
        val id = pledgePost(f)
        val first = record(f, id)
        val original = rawVerdict(id)
        assertEquals("PENDING", first.entries.last { it.kind == "READING" }.payload["availability"].asString())
        assertTrue(first.entries.last { it.kind == "READING" }.payload.path("model").isMissingNode)
        analyzer.fail = false
        mvc.securePost("/w/${f.workspace.token}/pledges/$id/trace/reading").andExpect { status { is3xxRedirection() } }
        val completed = record(f, id)
        assertEquals(2, analyzer.calls.get())
        assertEquals("INTACT", completed.integrity)
        assertEquals(first.entries, completed.entries.take(first.entries.size))
        assertEquals(original, rawVerdict(id))
        assertEquals("AVAILABLE", completed.entries.last().payload["availability"].asString())
        repeat(3) {
            provenance.record(pledges.findById(id).orElseThrow())
            readings.retry(f.workspace.token, id)
        }
        assertEquals(completed, record(f, id))
        assertEquals(2, analyzer.calls.get())
    }

    @Test fun `failed attempts exhaust durable budget without GET retries or invented result`() {
        val f = fixture()
        analyzer.fail = true
        val id = pledgePost(f)
        repeat(4) { assertTrue(readings.retry(f.workspace.token, id)) }
        val before = record(f, id)
        val payload = before.entries.last { it.kind == "READING" }.payload
        assertEquals(2, analyzer.calls.get())
        assertEquals("PENDING", payload["availability"].asString())
        assertFalse(payload["retryAllowed"].asBoolean())
        assertTrue(payload.path("quote").isMissingNode)
        val html = mvc.get("/w/${f.workspace.token}/pledges/$id/trace").andReturn().response.contentAsString
        assertContains(html, "Reading not completed")
        assertContains(html, "No automatic retry will run.")
        assertEquals(before, record(f, id))
        assertEquals(2, analyzer.calls.get())
    }

    @ParameterizedTest
    @ValueSource(strings = ["wish", "option", "offer", "workspace"])
    fun `changed source after success cannot be returned to callers or linked`(kind: String) {
        val f = fixture()
        assertNotNull(readings.assessOpen(f.workspace.token, f.wish.id))
        persistence.checkpoint {
            val sql = when (kind) {
                "wish" -> "update wishes set text = 'Changed wish' where id = :id"
                "option" -> "update product_options set name = 'Changed format' where id = :id"
                "offer" -> "update offers set title = 'Changed offer' where id = :id"
                else -> "update wishes set workspace_id = :workspace where id = :id"
            }
            val query = entities.createNativeQuery(sql).setParameter("id", when (kind) {
                "option" -> f.option.id
                "offer" -> f.offer.id
                else -> f.wish.id
            })
            if (kind == "workspace") query.setParameter("workspace", UUID.randomUUID())
            query.executeUpdate()
        }
        assertNull(runCatching { readings.assessOpen(f.workspace.token, f.wish.id) }.getOrNull())
        assertEquals(1, analyzer.calls.get())
    }

    @Test fun `concurrent duplicate requests share one durable reservation`() {
        val f = fixture()
        analyzer.entered = CountDownLatch(1)
        analyzer.release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { readings.assessOpen(f.workspace.token, f.wish.id) }
            assertTrue(analyzer.entered!!.await(5, TimeUnit.SECONDS))
            val duplicate = executor.submit { readings.assessOpen(f.workspace.token, f.wish.id) }
            duplicate.get(5, TimeUnit.SECONDS)
            assertEquals(1, analyzer.calls.get())
            analyzer.release!!.countDown()
            first.get(5, TimeUnit.SECONDS)
            assertEquals("AVAILABLE", readings.assessOpen(f.workspace.token, f.wish.id)?.get("availability")?.asString())
            assertEquals(1, analyzer.calls.get())
        } finally {
            analyzer.release!!.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun `retry requires CSRF and exact stored pledge workspace wish and offer`() {
        val f = fixture()
        analyzer.fail = true
        val id = pledgePost(f)
        val other = fixture()
        mvc.post("/w/${f.workspace.token}/pledges/$id/trace/reading").andExpect { status { isForbidden() } }
        mvc.securePost("/w/${other.workspace.token}/pledges/$id/trace/reading").andExpect { status { isNotFound() } }
        assertEquals(1, analyzer.calls.get())
        val unlinked = pledgePost(other, linked = false)
        assertFalse(readings.retry(other.workspace.token, unlinked))
        assertTrue(record(other, unlinked).entries.none { it.kind == "READING" })
        assertEquals(1, analyzer.calls.get())
    }

    @Test fun `explicit pledge fallback reads an unanalysed wish saved before publication`() {
        val f = fixture()
        persistence.checkpoint {
            entities.createNativeQuery("update wishes set created_at = :created where id = :id")
                .setParameter("created", now.minusSeconds(45)).setParameter("id", f.wish.id).executeUpdate()
        }
        val id = pledgePost(f)
        assertEquals(1, analyzer.calls.get())
        assertEquals("AVAILABLE", record(f, id).entries.last { it.kind == "READING" }.payload["availability"].asString())
    }

    @Test fun `original run reading is reused without another model call`() {
        val f = fixture()
        persistence.checkpoint {
            val output = AnalysisOutput(listOf(AnalyzedWish(f.wish.id, WishKind.REQUEST, emptyList())),
                listOf(WishVerdict(f.wish.id, f.option.id, CompatibilityVerdict.COMPATIBLE, "Original reading")),
                null, null, emptyList(), emptyList(), emptyList(), listOf(f.option.id))
            entities.createNativeQuery("update analysis_runs set result_json = :result where id = :id")
                .setParameter("result", mapper.writeValueAsString(output)).setParameter("id", f.offer.analysisRunId).executeUpdate()
        }
        val id = pledgePost(f)
        assertEquals(0, analyzer.calls.get())
        val record = record(f, id)
        assertTrue(record.entries.none { it.kind == "READING" })
        assertEquals("COMPATIBLE", record.entries.single { it.kind == "VERDICT" }.payload["verdict"].asString())
    }

    @Test fun `assessment retry and reconciliation never invent a missing historical record baseline`() {
        val f = fixture()
        val pledge = persistence.checkpoint {
            Pledge(f.campaign.id, 1, 650, "USD", createdAt = now, wishId = f.wish.id).also(entities::persist)
        }
        assertTrue(readings.retry(f.workspace.token, pledge.id))
        service.reconcile(CampaignRef(f.workspace.id, f.campaign.id))
        assertEquals("UNAVAILABLE", record(f, pledge.id).integrity)
        assertTrue(record(f, pledge.id).entries.isEmpty())
        assertEquals(1, analyzer.calls.get())
    }

    @Test fun `same wish assessed for another sealed offer has a distinct immutable scope`() {
        val f = fixture()
        val first = assertNotNull(readings.assess(f.workspace.id, f.offer.id, f.wish.id))
        val second = persistence.checkpoint {
            val offer = Offer(f.workspace.id, f.option.id, f.offer.analysisRunId, "Another card batch", "Postcards",
                emptyMap(), 700, "USD", "Ships later", "US", 1, 10).also(entities::persist)
            entities.persist(Campaign(offer.id, CampaignState.OPEN, openedAt = now.minusSeconds(30), closesAt = now.plusSeconds(3600)))
            records.recordOffer(offer)
            offer
        }
        val assessment = assertNotNull(readings.assess(f.workspace.id, second.id, f.wish.id))
        assertNotEquals(first["inputHash"], assessment["inputHash"])
        assertNotEquals(first["termsHash"], assessment["termsHash"])
        assertEquals(second.id.toString(), assessment["offerId"].asString())
        assertEquals(2, analyzer.calls.get())
        assertNull(readings.assess(UUID.randomUUID(), second.id, f.wish.id))
        assertEquals(2, analyzer.calls.get())
    }

    @Test fun `changed immutable reading source is reported without blocking later payment evidence`() {
        val f = fixture()
        val id = pledgePost(f)
        val first = record(f, id)
        val assessmentId = UUID.fromString(first.entries.single { it.kind == "READING" }.payload["assessmentId"].asString())
        persistence.checkpoint {
            entities.createNativeQuery("update decision_source_snapshots set payload = '{}' where source_id = :id")
                .setParameter("id", assessmentId).executeUpdate()
        }
        assertEquals("CHANGED", record(f, id).integrity)
        val pledge = pledges.findById(id).orElseThrow()
        pledge.paypalOrderId = "reconciled-order"
        pledges.saveAndFlush(pledge)
        provenance.record(pledge)
        val after = record(f, id)
        assertEquals(first.entries, after.entries.take(first.entries.size))
        assertEquals("PAYMENT", after.entries.last().kind)
        assertEquals("CHANGED", after.integrity)
    }

    @Test fun `saved later assessment is linked by reconciliation without another provider call`() {
        val f = fixture()
        val pledge = persistence.checkpoint {
            Pledge(f.campaign.id, 1, 650, "USD", createdAt = now, wishId = f.wish.id).also(entities::persist)
        }
        provenance.record(pledge)
        val before = record(f, pledge.id)
        readings.assessOpen(f.workspace.token, f.wish.id)
        service.reconcile(CampaignRef(f.workspace.id, f.campaign.id))
        val after = record(f, pledge.id)
        assertEquals(before.entries, after.entries.take(before.entries.size))
        assertEquals("AVAILABLE", after.entries.last().payload["availability"].asString())
        service.reconcile(CampaignRef(f.workspace.id, f.campaign.id))
        assertEquals(after, record(f, pledge.id))
        assertEquals(1, analyzer.calls.get())
    }

    private fun pledgePost(f: Fixture, linked: Boolean = true): UUID {
        gateway.fake.createOrderResults.add(PaymentResult.Received(OrderDetails(OrderId("order-${UUID.randomUUID()}")), PaymentStatus.CREATED))
        val response = mvc.securePost("/w/${f.workspace.token}/api/pledges") {
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(mapOf("quantity" to 1, "campaignId" to f.campaign.id,
                "wishId" to if (linked) f.wish.id else null))
        }.andExpect { status { isCreated() } }.andReturn()
        return UUID.fromString(mapper.readTree(response.response.contentAsString)["pledgeId"].asString())
    }
    private fun record(f: Fixture, id: UUID) = assertNotNull(queries.findPledgeByToken(f.workspace.token, id))
    private fun rawVerdict(id: UUID) = persistence.checkpoint {
        entities.createQuery("select e from DecisionRecordEntry e where e.pledgeId = :id and e.kind = 'VERDICT'", DecisionRecordEntry::class.java)
            .setParameter("id", id).singleResult.let { it.payload to it.hash }
    }
    private fun fixture(): Fixture = persistence.checkpoint {
        val workspace = Workspace("readng-${UUID.randomUUID()}", now).also(entities::persist).also { owned.add(it.id) }
        val option = Option(workspace.id, "Postcards", "card", emptyMap(), emptyMap(), 500, "USD", 1, 10, "Two weeks", "US").also(entities::persist)
        val output = AnalysisOutput(emptyList(), emptyList(), null, "No original wishes", emptyList(), emptyList(), emptyList(), listOf(option.id))
        val run = AnalysisRun(workspace.id, "original-model", "original-input", mapper.writeValueAsString(output), createdAt = now.minusSeconds(60)).also(entities::persist)
        val offer = Offer(workspace.id, option.id, run.id, "Card batch", "Postcards", emptyMap(), 650, "USD", "Ships in two weeks", "US", 1, 10).also(entities::persist)
        val campaign = Campaign(offer.id, CampaignState.OPEN, openedAt = now.minusSeconds(30), closesAt = now.plusSeconds(3600)).also(entities::persist)
        val wish = Wish(workspace.id, "Exact postcard wish", WishSource.ONSITE, "Private Fan Name", now).also(entities::persist)
        records.recordOffer(offer)
        Fixture(workspace, option, offer, campaign, wish)
    }
    private data class Fixture(val workspace: Workspace, val option: Option, val offer: Offer, val campaign: Campaign, val wish: Wish)

    @TestConfiguration class Configuration {
        @Bean @Primary fun readingAnalyzer() = ReadingAnalyzer()
    }
}

class ReadingAnalyzer : WishAnalyzer {
    val calls = AtomicInteger()
    var fail = false
    var entered: CountDownLatch? = null
    var release: CountDownLatch? = null
    override fun analyze(options: List<Option>, wishes: List<Wish>): AnalysisResult = AnalysisResult.Failure(AnalysisFailure.NOT_CONFIGURED)
    override fun classify(option: Option, wish: Wish): AnalysisResult {
        calls.incrementAndGet()
        entered?.countDown()
        release?.let { check(it.await(5, TimeUnit.SECONDS)) }
        if (fail) return AnalysisResult.Failure(AnalysisFailure.NOT_CONFIGURED)
        val output = AnalysisOutput(listOf(AnalyzedWish(wish.id, WishKind.REQUEST,
            listOf(WishNeed(NeedStrength.HARD, NeedAttribute.FORMAT, "postcard", wish.text)))),
            listOf(WishVerdict(wish.id, option.id, CompatibilityVerdict.COMPATIBLE, "Matches format")),
            null, null, emptyList(), emptyList(), emptyList(), listOf(option.id))
        return AnalysisResult.Success("synthetic-reading-model", AnalysisValidationResult.Accepted(output, emptyList()),
            analysisInputHash(listOf(option), listOf(wish)), completedAt)
    }
    companion object { val completedAt: Instant = Instant.parse("2026-01-01T00:00:01.123456789Z") }
}

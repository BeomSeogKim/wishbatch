package app.campaign.provenance

import app.campaign.model.AnalysisRun
import app.campaign.model.Campaign
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Offer
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.model.ShippingAddress
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.model.WishSource
import app.campaign.model.Workspace
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalyzedWish
import app.campaign.port.WishVerdict
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DecisionRecordTests @Autowired constructor(
    private val entities: EntityManager,
    private val records: DecisionRecordService,
    private val query: DecisionRecordQuery,
    private val mapper: JsonMapper,
    private val mvc: MockMvc,
) {
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `canonical hash ignores object order but preserves exact text array order and large integers`() {
        val hashing = DecisionRecordHash(mapper)
        val a = mapOf("z" to 9_007_199_254_740_993L, "a" to mapOf("b" to "\u00e9\n", "a" to listOf(1, 2)))
        val b = mapOf("a" to mapOf("a" to listOf(1, 2), "b" to "\u00e9\n"), "z" to 9_007_199_254_740_993L)
        assertEquals(hashing.canonical(a), hashing.canonical(b))
        assertContains(hashing.canonical(a), "9007199254740993")
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hashing.sha256(""))
        assertNotEquals(hashing.sha256(hashing.canonical(listOf(1, 2))), hashing.sha256(hashing.canonical(listOf(2, 1))))
        assertNotEquals(hashing.sha256("exact text"), hashing.sha256("exact  text"))
    }

    @Test
    fun `snapshots persist and payment checkpoints append once without fabricating a wish link`() {
        val fixture = fixture()
        snapshot(fixture)
        val first = document(fixture).pledges.single()
        assertEquals(listOf("WISH", "VERDICT", "OFFER", "PAYMENT"), first.entries.map { it.kind })
        assertEquals("UNAVAILABLE", first.entries[0].payload["availability"].asString())
        assertEquals("UNAVAILABLE", first.entries[1].payload["availability"].asString())
        assertEquals(fixture.run.id.toString(), first.entries[1].payload["analysisRunId"].asString())
        assertEquals("synthetic-model", first.entries[1].payload["model"].asString())
        assertEquals("original-input", first.entries[1].payload["inputHash"].asString())
        assertEquals("AVAILABLE", first.entries[2].payload["availability"].asString())
        val termsHash = first.entries[2].payload["termsHash"].asString()
        assertEquals(document(fixture).sources.single { it.sourceType == "OFFER" }.hash, termsHash)
        records.recordAnalysis(fixture.run, listOf(fixture.wish))
        records.recordOffer(fixture.offer)
        records.recordPledge(fixture.pledge)
        assertEquals(first, document(fixture).pledges.single())

        fixture.pledge.paypalOrderId = "sandbox-order"
        records.recordPledge(fixture.pledge)
        fixture.pledge.authorizationId = "sandbox-authorization"
        fixture.pledge.authorizationExpiresAt = now.plusSeconds(60)
        fixture.pledge.state = PledgeState.AUTHORIZED
        records.recordPledge(fixture.pledge)
        fixture.pledge.captureId = "sandbox-capture"
        fixture.pledge.state = PledgeState.CAPTURE_PENDING
        records.recordPledge(fixture.pledge)
        fixture.pledge.state = PledgeState.CAPTURED
        records.recordPledge(fixture.pledge)
        fixture.pledge.refundId = "sandbox-refund"
        fixture.pledge.state = PledgeState.REFUND_PENDING
        records.recordPledge(fixture.pledge)
        fixture.pledge.state = PledgeState.REFUNDED
        records.recordPledge(fixture.pledge)
        entities.flush()
        entities.clear()
        val saved = document(fixture).pledges.single()
        assertEquals("INTACT", saved.integrity)
        assertEquals(10, saved.entries.size)
        assertEquals(first.entries, saved.entries.take(4))
        assertNull(saved.entries.first().previousHash)
        saved.entries.zipWithNext().forEach { (before, after) -> assertEquals(before.hash, after.previousHash) }
        assertEquals(saved.entries.last().hash, saved.lastHash)
        assertEquals("REFUNDED", saved.entries.last().payload["state"].asString())
    }

    @Test
    fun `pending failed released and simulated outcomes remain distinct`() {
        val fixture = fixture()
        snapshot(fixture)
        fixture.pledge.authorizationId = "sandbox-authorization"
        for (state in listOf(PledgeState.AUTHORIZED, PledgeState.CAPTURE_FAILED, PledgeState.VOID_FAILED, PledgeState.VOIDED)) {
            fixture.pledge.state = state
            fixture.pledge.lastError = if (state == PledgeState.CAPTURE_FAILED) "SIMULATED_CAPTURE_FAILURE" else null
            records.recordPledge(fixture.pledge)
        }
        val payments = document(fixture).pledges.single().entries.filter { it.kind == "PAYMENT" }
        assertEquals(listOf("CREATED", "AUTHORIZED", "CAPTURE_FAILED", "VOID_FAILED", "VOIDED"),
            payments.map { it.payload["state"].asString() })
        assertTrue(payments[2].payload["simulatedCaptureFailure"].asBoolean())
        assertTrue(payments.all { it.payload["captureId"].isNull })
    }

    @Test
    fun `source and payment edits are detected without rewriting the saved chain`() {
        val fixture = fixture()
        fixture.pledge.paypalOrderId = "original-order"
        snapshot(fixture)
        val original = document(fixture)
        for ((table, column, value, before, id) in listOf(
            listOf("wishes", "text", "Edited text", fixture.wish.text, fixture.wish.id),
            listOf("analysis_runs", "input_hash", "edited-hash", fixture.run.inputHash, fixture.run.id),
            listOf("offers", "delivery_terms", "Edited delivery", fixture.offer.deliveryTerms, fixture.offer.id),
            listOf("pledges", "paypal_order_id", "edited-order", "original-order", fixture.pledge.id),
        )) {
            entities.createNativeQuery("update $table set $column = :value where id = :id")
                .setParameter("value", value).setParameter("id", id).executeUpdate()
            entities.clear()
            val changed = document(fixture)
            assertEquals("CHANGED", changed.pledges.single().integrity)
            assertEquals(original.pledges.single().entries, changed.pledges.single().entries)
            assertEquals(original.sources, changed.sources)
            entities.createNativeQuery("update $table set $column = :value where id = :id")
                .setParameter("value", before).setParameter("id", id).executeUpdate()
            entities.clear()
            assertEquals("INTACT", document(fixture).pledges.single().integrity)
        }
    }

    @Test
    fun `stored payload edits and tail deletion fail integrity verification`() {
        val fixture = fixture()
        snapshot(fixture)
        val original = document(fixture).pledges.single()
        entities.createNativeQuery("update decision_record_entries set payload = '{}' where pledge_id = :id and position = 1")
            .setParameter("id", fixture.pledge.id).executeUpdate()
        entities.clear()
        assertContains(document(fixture).pledges.single().issues, "Stored hash chain failed integrity verification.")
        entities.createNativeQuery("update decision_record_entries set payload = :payload where pledge_id = :id and position = 1")
            .setParameter("payload", DecisionRecordHash(mapper).canonical(original.entries.first().payload))
            .setParameter("id", fixture.pledge.id).executeUpdate()
        entities.clear()
        assertEquals("INTACT", document(fixture).pledges.single().integrity)
        entities.createNativeQuery("delete from decision_record_entries where pledge_id = :id and position = 4")
            .setParameter("id", fixture.pledge.id).executeUpdate()
        entities.clear()
        val changed = document(fixture).pledges.single()
        assertEquals(4, changed.entryCount)
        assertEquals(3, changed.entries.size)
        assertEquals(original.lastHash, changed.lastHash)
        assertEquals("CHANGED", changed.integrity)
    }

    @Test
    fun `changing the campaign offer association is detected`() {
        val fixture = fixture()
        snapshot(fixture)
        val replacement = Offer(fixture.workspace.id, fixture.offer.optionId, fixture.run.id, "Other offer",
            "Other terms", emptyMap(), 300, "USD", "Other delivery", "US", 1, 5).also(entities::persist)
        entities.flush()
        entities.createNativeQuery("update campaigns set offer_id = :offer where id = :id")
            .setParameter("offer", replacement.id).setParameter("id", fixture.pledge.campaignId).executeUpdate()
        entities.clear()
        assertEquals("CHANGED", document(fixture).pledges.single().integrity)
    }

    @Test
    fun `deleted source is detected and immutable snapshots cannot be replaced`() {
        val fixture = fixture()
        snapshot(fixture)
        entities.createNativeQuery("delete from decision_source_snapshots where source_id = :id")
            .setParameter("id", fixture.offer.id).executeUpdate()
        entities.clear()
        assertEquals("CHANGED", document(fixture).pledges.single().integrity)
        val changed = entities.find(Pledge::class.java, fixture.pledge.id)
        changed.paypalOrderId = "new-order"
        assertFailsWith<IllegalStateException> { records.recordPledge(changed) }
    }

    @Test
    fun `analysis snapshot refuses to replace its original input text`() {
        val fixture = fixture()
        records.recordAnalysis(fixture.run, listOf(fixture.wish))
        val edited = Wish(fixture.workspace.id, "Edited original", WishSource.ONSITE, createdAt = now, id = fixture.wish.id)
        assertFailsWith<IllegalStateException> { records.recordAnalysis(fixture.run, listOf(edited)) }
    }

    @Test
    fun `analysis snapshot rejects input from another workspace`() {
        val fixture = fixture()
        val foreign = Wish(UUID.randomUUID(), fixture.wish.text, WishSource.ONSITE, createdAt = now, id = fixture.wish.id)
        assertFailsWith<IllegalArgumentException> { records.recordAnalysis(fixture.run, listOf(foreign)) }
    }

    @Test
    fun `offer snapshot cannot be replaced with edited terms`() {
        val fixture = fixture()
        records.recordOffer(fixture.offer)
        entities.createNativeQuery("update offers set delivery_terms = 'Edited delivery' where id = :id")
            .setParameter("id", fixture.offer.id).executeUpdate()
        entities.clear()
        assertFailsWith<IllegalStateException> { records.recordOffer(entities.find(Offer::class.java, fixture.offer.id)) }
    }

    @Test
    fun `empty workspace has no fabricated source or pledge records`() {
        val workspace = Workspace(UUID.randomUUID().toString(), now).also(entities::persist)
        entities.flush()
        val document = assertNotNull(query.findByToken(workspace.token))
        assertTrue(document.sources.isEmpty())
        assertTrue(document.pledges.isEmpty())
        val html = mvc.get("/w/${workspace.token}/maker/decision-record")
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(html, "No pledges in this workspace.")
        assertContains(html, "No analysis or offer snapshots were recorded.")
    }

    @Test
    fun `legacy reads create no records and missing earlier snapshots remain unavailable`() {
        val fixture = fixture()
        repeat(2) {
            assertEquals("UNAVAILABLE", document(fixture).pledges.single().integrity)
            mvc.get("/w/${fixture.workspace.token}/maker/decision-record").andExpect { status { isOk() } }
            mvc.get("/w/${fixture.workspace.token}/maker/decision-record.json").andExpect { status { isOk() } }
        }
        assertEquals(0L, entities.createQuery("select count(r) from PledgeDecisionRecord r", Long::class.java).singleResult)
        assertTrue(document(fixture).sources.isEmpty())
        records.recordPledge(fixture.pledge)
        assertEquals("UNAVAILABLE", document(fixture).pledges.single().entries[2].payload["availability"].asString())
        records.recordAnalysis(fixture.run, listOf(fixture.wish))
        records.recordOffer(fixture.offer)
        records.recordPledge(fixture.pledge)
        assertEquals(4, document(fixture).pledges.single().entries.size)
        assertEquals("UNAVAILABLE", document(fixture).pledges.single().entries[2].payload["availability"].asString())
    }

    @Test
    fun `maker HTML escapes inputs and JSON download excludes buyer details across workspaces`() {
        val fixture = fixture()
        snapshot(fixture)
        val path = "/w/${fixture.workspace.token}/maker/decision-record"
        val html = mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(html, "Download JSON")
        assertContains(html, "&lt;script&gt;")
        assertFalse(html.contains("<script>"))
        assertContains(html, "Wish association and individual verdict are unavailable")
        val json = mvc.get("$path.json").andExpect {
            status { isOk() }
            content { contentType("application/json") }
            header { string("Content-Disposition", "attachment; filename=\"decision-record.json\"") }
            header { string("Cache-Control", "no-store") }
        }.andReturn().response.contentAsString
        assertEquals(mapper.valueToTree(document(fixture)), mapper.readTree(json))
        for (privateValue in listOf("private-name", "private-email", "private-street", "private-error", "private-display")) {
            assertFalse(json.contains(privateValue))
            assertFalse(html.contains(privateValue))
        }
        val other = fixture()
        assertFalse(mvc.get("/w/${other.workspace.token}/maker/decision-record.json")
            .andReturn().response.contentAsString.contains(fixture.pledge.id.toString()))
        mvc.get("/w/missing/maker/decision-record").andExpect { status { isNotFound() } }
        mvc.get("/w/missing/maker/decision-record.json").andExpect { status { isNotFound() } }
        assertNull(query.findByToken("missing"))
    }

    private fun snapshot(fixture: Fixture) {
        records.recordAnalysis(fixture.run, listOf(fixture.wish))
        records.recordOffer(fixture.offer)
        records.recordPledge(fixture.pledge)
    }

    private fun document(fixture: Fixture) = assertNotNull(query.findByToken(fixture.workspace.token))

    private fun fixture(): Fixture {
        val workspace = Workspace(UUID.randomUUID().toString(), now).also(entities::persist)
        val wish = Wish(workspace.id, "Please make a matte <script>print</script>.", WishSource.ONSITE,
            "private-display", now).also(entities::persist)
        val optionId = UUID.randomUUID()
        val output = AnalysisOutput(listOf(AnalyzedWish(wish.id, WishKind.REQUEST, emptyList())),
            listOf(WishVerdict(wish.id, optionId, CompatibilityVerdict.COMPATIBLE, "Matte option")),
            null, "No proposal", emptyList(), emptyList(), emptyList(), listOf(optionId))
        val run = AnalysisRun(workspace.id, "synthetic-model", "original-input", mapper.writeValueAsString(output),
            isSeed = true, createdAt = now).also(entities::persist)
        val offer = Offer(workspace.id, optionId, run.id, "Print <script>offer</script>", "Matte print",
            mapOf("finish" to "matte"), 9_007_199_254_740_993L, "USD", "Shipping included", "US", 1, 10,
            supportingWishIds = listOf(wish.id)).also(entities::persist)
        val campaign = Campaign(offer.id).also(entities::persist)
        val pledge = Pledge(campaign.id, 1, offer.unitPrice, "USD", payerEmailMasked = "private-email",
            shippingAddress = ShippingAddress("private-name", "private-street", null, "CA", "City", "00000", "US"),
            lastError = "private-error", createdAt = now).also(entities::persist)
        entities.flush()
        return Fixture(workspace, wish, run, offer, pledge)
    }

    private data class Fixture(val workspace: Workspace, val wish: Wish, val run: AnalysisRun, val offer: Offer, val pledge: Pledge)
}

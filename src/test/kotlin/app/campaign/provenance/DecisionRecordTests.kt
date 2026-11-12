package app.campaign.provenance

import app.campaign.followup.FollowupAnswerRecord
import app.campaign.followup.FollowupQuestion
import app.campaign.followup.FollowupReviewRecorded
import app.campaign.model.NeedAttribute
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
import app.campaign.port.SecondReading
import app.campaign.port.WishReadingComparison
import app.campaign.review.WishReviewOverride
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
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
    private val publisher: ApplicationEventPublisher,
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
        val pledge = entities.find(Pledge::class.java, fixture.pledge.id)
        pledge.state = PledgeState.REFUNDED
        pledge.refundId = "later-refund"
        assertEquals("Stored decision record failed integrity verification",
            assertFailsWith<IllegalStateException> { records.recordPledge(pledge) }.message)
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
        assertEquals("Stored decision record identity changed", assertFailsWith<IllegalStateException> {
            records.recordPledge(entities.find(Pledge::class.java, fixture.pledge.id))
        }.message)
    }

    @Test
    fun `deleted source remains visible after later payment checkpoints`() {
        val fixture = fixture()
        snapshot(fixture)
        entities.createNativeQuery("delete from decision_source_snapshots where source_id = :id")
            .setParameter("id", fixture.offer.id).executeUpdate()
        entities.clear()
        assertEquals("CHANGED", document(fixture).pledges.single().integrity)
        val changed = entities.find(Pledge::class.java, fixture.pledge.id)
        changed.paypalOrderId = "new-order"
        records.recordPledge(changed)
        entities.flush()
        entities.clear()
        val saved = document(fixture).pledges.single()
        assertEquals("CHANGED", saved.integrity)
        assertEquals(5, saved.entries.size)
        assertEquals("new-order", saved.entries.last().payload["orderId"].asString())
        assertTrue(document(fixture).sources.none { it.sourceId == fixture.offer.id })
    }

    @Test
    fun `edited wish text does not block later refund or release and reads still report source drift`() {
        for (state in listOf(PledgeState.REFUNDED, PledgeState.VOIDED)) {
            val fixture = fixture()
            fixture.pledge.paypalOrderId = "sandbox-order"
            fixture.pledge.authorizationId = "sandbox-authorization"
            fixture.pledge.state = if (state == PledgeState.REFUNDED) PledgeState.CAPTURED else PledgeState.AUTHORIZED
            if (state == PledgeState.REFUNDED) fixture.pledge.captureId = "sandbox-capture"
            snapshot(fixture)
            val baseline = document(fixture)
            entities.createNativeQuery("update wishes set text = 'Edited wish after analysis' where id = :id")
                .setParameter("id", fixture.wish.id).executeUpdate()
            entities.clear()
            val pledge = entities.find(Pledge::class.java, fixture.pledge.id)
            pledge.state = state
            if (state == PledgeState.REFUNDED) pledge.refundId = "sandbox-refund"
            records.recordPledge(pledge)
            entities.flush()
            entities.clear()
            assertEquals(state, entities.find(Pledge::class.java, fixture.pledge.id).state)
            val saved = document(fixture)
            assertEquals(baseline.sources, saved.sources)
            assertEquals(baseline.pledges.single().entries, saved.pledges.single().entries.dropLast(1))
            assertEquals(state.name, saved.pledges.single().entries.last().payload["state"].asString())
            val expectedIssue = "Analysis input or offer differs from its recorded snapshot, or a source is missing."
            assertEquals(listOf(expectedIssue), saved.pledges.single().issues)
            val path = "/w/${fixture.workspace.token}/maker/decision-record"
            val json = mapper.readTree(mvc.get("$path.json").andExpect { status { isOk() } }
                .andReturn().response.contentAsString)
            assertEquals("CHANGED", json["pledges"][0]["integrity"].asString())
            assertEquals(listOf(expectedIssue), json["pledges"][0]["issues"].toList().map { it.asString() })
            assertContains(mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString, expectedIssue)
        }
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
        val other = fixture()
        snapshot(other)
        val otherBefore = document(other)
        val fixture = fixture()
        repeat(2) {
            assertEquals("UNAVAILABLE", document(fixture).pledges.single().integrity)
            mvc.get("/w/${fixture.workspace.token}/maker/decision-record").andExpect { status { isOk() } }
            mvc.get("/w/${fixture.workspace.token}/maker/decision-record.json").andExpect { status { isOk() } }
        }
        assertEquals(0L, entities.createQuery(
            "select count(r) from PledgeDecisionRecord r where r.workspaceId = :workspace", Long::class.java,
        ).setParameter("workspace", fixture.workspace.id).singleResult)
        assertEquals(otherBefore, document(other))
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
        assertContains(html, "A pledge without a wish traces to the offer's evidence set.")
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

    @Test
    fun `linked wish records exact text offered reading agreement and maker revision`() {
        val fixture = fixture(linkWish = true)
        val revision = WishReviewOverride(fixture.workspace.id, fixture.run.id, fixture.wish.id, fixture.offer.optionId,
            CompatibilityVerdict.INCOMPATIBLE, CompatibilityVerdict.COMPATIBLE, "This finish cannot meet the request.", now, 1)
            .also(entities::persist)
        snapshot(fixture)
        val saved = document(fixture).pledges.single()
        val wish = saved.entries[0].payload
        assertEquals("AVAILABLE", wish["availability"].asString())
        assertEquals(fixture.wish.id.toString(), wish["wishId"].asString())
        assertEquals(fixture.wish.text, wish["text"].asString())
        assertEquals(DecisionRecordHash(mapper).sha256(fixture.wish.text), wish["textHash"].asString())
        val verdict = saved.entries[1].payload
        assertEquals("COMPATIBLE", verdict["originalVerdict"].asString())
        assertEquals("INCOMPATIBLE", verdict["verdict"].asString())
        assertEquals(revision.id.toString(), verdict["makerOverride"]["id"].asString())
        assertEquals("MAKER", verdict["review"]["source"].asString())
        assertFalse(verdict["readingAgreement"]["needsYourEyes"].asBoolean())
        assertEquals(fixture.offer.optionId.toString(), verdict["optionId"].asString())
        assertTrue(assertNotNull(saved.chainVerification).intact)
        assertNotNull(saved.entries.last().payload["recordedAt"])
        assertEquals(saved, query.findPledgeByToken(fixture.workspace.token, fixture.pledge.id))
        assertNull(query.findPledgeByToken(fixture().workspace.token, fixture.pledge.id))
        records.recordPledge(fixture.pledge)
        assertEquals(saved, document(fixture).pledges.single())
    }

    @Test
    fun `non request wish preserves its known reading without inventing an option verdict`() {
        val fixture = fixture(linkWish = true, wishKind = WishKind.NOT_A_REQUEST)
        snapshot(fixture)
        val verdict = document(fixture).pledges.single().entries[1].payload
        assertEquals("AVAILABLE", verdict["availability"].asString())
        assertEquals("NOT_A_REQUEST", verdict["kind"].asString())
        assertTrue(verdict["verdict"].isNull)
        assertEquals("This was read as not a request.", verdict["reason"].asString())
    }

    @Test
    fun `fan resolution snapshot preserves all contributing questions and answers`() {
        val fixture = fixture(linkWish = true)
        val questions = listOf("finish", "size").map { key ->
            FollowupQuestion(fixture.workspace.id, fixture.run.id, fixture.offer.optionId,
                if (key == "finish") NeedAttribute.FINISH else NeedAttribute.SIZE, key,
                "Would this $key work?", listOf("yes", "no"), listOf(fixture.wish.id), now, approved = true)
                .also(entities::persist)
        }
        val answers = questions.map {
            FollowupAnswerRecord(fixture.workspace.id, fixture.run.id, fixture.offer.optionId,
                it.id, fixture.wish.id, "yes", now).also(entities::persist)
        }
        val review = WishReviewOverride(fixture.workspace.id, fixture.run.id, fixture.wish.id, fixture.offer.optionId,
            CompatibilityVerdict.COMPATIBLE, CompatibilityVerdict.CONDITIONAL, "Fan confirmed both conditions.", now, 1,
            source = "FAN_ANSWER", answerId = answers.last().id).also(entities::persist)
        val originalRun = fixture.run.resultJson
        publisher.publishEvent(FollowupReviewRecorded(review, answers, questions))
        records.recordWishReview(review, answers.reversed(), questions.reversed())
        snapshot(fixture)
        val document = document(fixture)
        val source = document.sources.single { it.sourceType == "REVIEW" }
        assertTrue(source.intact)
        assertEquals(2, source.payload["answers"].size())
        assertEquals(2, source.payload["questions"].size())
        assertEquals(originalRun, fixture.run.resultJson)
        val verdict = document.pledges.single().entries[1].payload
        assertEquals("FAN_ANSWER", verdict["review"]["source"].asString())
        assertTrue(verdict["makerOverride"].isNull)
        assertEquals(source.hash, verdict["reviewSnapshotHash"].asString())
        assertEquals(source.payload, verdict["reviewEvidence"])
        assertEquals("INTACT", document.pledges.single().integrity)
    }

    @Test
    fun `wish free pledge explicitly traces to offer evidence without inventing personal words`() {
        val fixture = fixture()
        snapshot(fixture)
        val wish = document(fixture).pledges.single().entries.first().payload
        assertEquals("OFFER_EVIDENCE", wish["traceScope"].asString())
        assertTrue(wish["text"].isNull)
        assertTrue(wish["wishId"].isNull)
        assertEquals(listOf(fixture.wish.id.toString()), wish["supportingWishIds"].toList().map { it.asString() })
    }

    @Test
    fun `wish added after offered analysis is linked without inventing a reading`() {
        val fixture = fixture()
        snapshot(fixture)
        val laterWish = Wish(fixture.workspace.id, "Could this come in blue?", WishSource.ONSITE, createdAt = now)
            .also(entities::persist)
        val pledge = Pledge(fixture.pledge.campaignId, 1, fixture.offer.unitPrice, "USD", createdAt = now,
            wishId = laterWish.id).also(entities::persist)
        records.recordPledge(pledge)
        val saved = assertNotNull(query.findPledgeByToken(fixture.workspace.token, pledge.id))
        assertEquals(laterWish.text, saved.entries[0].payload["text"].asString())
        assertEquals("UNAVAILABLE", saved.entries[1].payload["availability"].asString())
        assertTrue(saved.entries[1].payload["verdict"].isNull)
        assertEquals("INTACT", saved.integrity)
    }

    @Test
    fun `verification returns first changed entry and tampered copy never changes stored rows`() {
        val fixture = fixture(linkWish = true)
        snapshot(fixture)
        val before = document(fixture)
        val simulation = assertNotNull(query.simulateTamperedCopyByToken(fixture.workspace.token))
        assertTrue(simulation.usesStoredRecord)
        assertTrue(simulation.original.intact)
        assertFalse(simulation.tamperedCopy.intact)
        assertEquals(1, simulation.tamperedCopy.firstBrokenPosition)
        assertEquals(before, document(fixture))
        entities.createNativeQuery("update decision_record_entries set payload = '{}' where pledge_id = :id and position = 2")
            .setParameter("id", fixture.pledge.id).executeUpdate()
        entities.clear()
        val changed = assertNotNull(document(fixture).pledges.single().chainVerification)
        assertFalse(changed.intact)
        assertEquals(2, changed.firstBrokenPosition)
        assertEquals(1, changed.checkedEntries)
    }

    @Test
    fun `verification action reports intact and first broken entry`() {
        val fixture = fixture()
        snapshot(fixture)
        val path = "/w/${fixture.workspace.token}/maker/decision-record/verify"
        assertContains(mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString, "Chain intact.")
        entities.createNativeQuery("delete from decision_record_entries where pledge_id = :id and position = 4")
            .setParameter("id", fixture.pledge.id).executeUpdate()
        entities.clear()
        val html = mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(html, "Chain breaks at entry <span>4</span>")
        mvc.get("/w/missing/maker/decision-record/verify").andExpect { status { isNotFound() } }
    }

    private fun snapshot(fixture: Fixture) {
        records.recordAnalysis(fixture.run, listOf(fixture.wish))
        records.recordOffer(fixture.offer)
        records.recordPledge(fixture.pledge)
    }

    private fun document(fixture: Fixture) = assertNotNull(query.findByToken(fixture.workspace.token))

    private fun fixture(linkWish: Boolean = false, wishKind: WishKind = WishKind.REQUEST): Fixture {
        val workspace = Workspace(UUID.randomUUID().toString(), now).also(entities::persist)
        val wish = Wish(workspace.id, "Please make a matte <script>print</script>.", WishSource.ONSITE,
            "private-display", now).also(entities::persist)
        val optionId = UUID.randomUUID()
        val output = AnalysisOutput(listOf(AnalyzedWish(wish.id, wishKind, emptyList())),
            if (wishKind == WishKind.REQUEST) listOf(WishVerdict(wish.id, optionId, CompatibilityVerdict.COMPATIBLE, "Matte option")) else emptyList(),
            null, "No proposal", emptyList(), emptyList(), emptyList(), listOf(optionId),
            SecondReading(1, 1, listOf(WishReadingComparison(wish.id, optionId, wishKind,
                wishKind, CompatibilityVerdict.COMPATIBLE.takeIf { wishKind == WishKind.REQUEST },
                CompatibilityVerdict.COMPATIBLE.takeIf { wishKind == WishKind.REQUEST }))))
        val run = AnalysisRun(workspace.id, "synthetic-model", "original-input", mapper.writeValueAsString(output),
            isSeed = true, createdAt = now).also(entities::persist)
        val offer = Offer(workspace.id, optionId, run.id, "Print <script>offer</script>", "Matte print",
            mapOf("finish" to "matte"), 9_007_199_254_740_993L, "USD", "Shipping included", "US", 1, 10,
            supportingWishIds = listOf(wish.id)).also(entities::persist)
        val campaign = Campaign(offer.id).also(entities::persist)
        val pledge = Pledge(campaign.id, 1, offer.unitPrice, "USD", payerEmailMasked = "private-email",
            shippingAddress = ShippingAddress("private-name", "private-street", null, "CA", "City", "00000", "US"),
            lastError = "private-error", createdAt = now, wishId = if (linkWish) wish.id else null).also(entities::persist)
        entities.flush()
        return Fixture(workspace, wish, run, offer, pledge)
    }

    private data class Fixture(val workspace: Workspace, val wish: Wish, val run: AnalysisRun, val offer: Offer, val pledge: Pledge)
}

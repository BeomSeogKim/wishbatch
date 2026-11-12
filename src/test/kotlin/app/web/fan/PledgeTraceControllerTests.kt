package app.web.fan

import app.campaign.provenance.DecisionEntryView
import app.campaign.provenance.DecisionRecordQuery
import app.campaign.provenance.PledgeDecisionView
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest
@AutoConfigureMockMvc
class PledgeTraceControllerTests @Autowired constructor(private val mvc: MockMvc, private val mapper: JsonMapper) {
    @MockitoBean
    private lateinit var records: DecisionRecordQuery

    private val token = "trace".padEnd(43, 'a')
    private val pledgeId = UUID.randomUUID()

    @Test
    fun `fan trail renders recorded words terms payment checkpoints and hashes without machine states`() {
        val record = record(
            "WISH" to """{"text":"I would like a blue postcard.","textHash":"wish-hash","traceScope":"WISH"}""",
            "VERDICT" to """{"verdict":"CONDITIONAL","reason":"Blue is an available finish.","model":"synthetic-model","analysisRunId":"00000000-0000-0000-0000-000000000001","inputHash":"input-hash","readingAgreement":{"needsYourEyes":true},"review":{"verdict":"COMPATIBLE","source":"MAKER","reason":"Blue finish confirmed.","revision":2}}""",
            "OFFER" to """{"offerId":"00000000-0000-0000-0000-000000000002","termsHash":"terms-hash","terms":{"title":"Recorded postcards","specText":"Five blue postcards","unitPriceMinor":650,"currency":"USD","deliveryTerms":"Ships in two weeks","shipRegion":"US","minBatch":3,"capacity":10,"chosenVariants":{"finish":"blue"}}}""",
            "PAYMENT" to """{"state":"AUTHORIZED","quantity":3,"amountMinor":1950,"currency":"USD","orderId":"ORDER-123456789012345","authorizationId":"AUTH-123456789012345","recordedAt":"2026-11-12T15:01:02.123456Z"}""",
            "PAYMENT" to """{"state":"CAPTURED","captureId":"CAPTURE-123456789012345","recordedAt":"2026-11-12T15:02:03.654321Z"}""",
        )
        `when`(records.findPledgeByToken(token, pledgeId)).thenReturn(record)
        val html = mvc.get("/w/$token/pledges/$pledgeId/trace").andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            view { name("fan/trace") }
        }.andReturn().response.contentAsString
        listOf("I would like a blue postcard.", "Depends", "Blue finish confirmed.", "Recorded postcards",
            "USD 6.50", "USD 19.50", "Authorized hold", "Paid", "wish-hash", "terms-hash", "hash-5",
            "The two readings differed.", "title=\"CAPTURE-123456789012345\"")
            .forEach { assertContains(html, it) }
        listOf(">AUTHORIZED<", ">CAPTURED<", ">CONDITIONAL<", ">INTACT<", "2026-11-12T15:02:03.654321Z", "minor units")
            .forEach { assertFalse(html.contains(it), it) }
    }

    @Test
    fun `unlinked and legacy evidence stays unavailable without reconstructing a wish`() {
        val noWish = record(
            "WISH" to """{"traceScope":"OFFER_EVIDENCE","supportingWishIds":["first","second"]}""",
            "VERDICT" to """{"availability":"UNAVAILABLE","reason":"This wish has no recorded reading."}""",
        )
        `when`(records.findPledgeByToken(token, pledgeId)).thenReturn(noWish)
        val html = mvc.get("/w/$token/pledges/$pledgeId/trace").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertContains(html, "No wish is linked to this pledge.")
        assertContains(html, "offer&#39;s recorded evidence set")
        assertContains(html, "Selected supporting evidence: <span>2</span> wishes.")
        assertContains(html, "This sample is not the total interested count.")
        assertContains(html, "An individual reading is unavailable for this wish and offered option.")
        assertFalse(html.contains("A second reading was not recorded"))
        assertFalse(html.contains("This wish has no recorded reading."))
        assertContains(html, "The earlier offer snapshot is unavailable.")
        assertNull(pledgeTrace(noWish).wish)
        val legacy = record("PAYMENT" to """{"state":"CAPTURED","createdAt":"2026-01-01T00:00:00Z"}""")
        assertNull(pledgeTrace(legacy).payments.single().recordedAt)
    }

    @Test
    fun `repeated order checkpoints do not claim another creation and retain their hashes`() {
        val history = record(
            "PAYMENT" to """{"state":"CREATED","recordedAt":"2026-11-12T15:00:00Z"}""",
            "PAYMENT" to """{"state":"CREATED","orderId":"ORDER-123","recordedAt":"2026-11-12T15:00:01Z"}""",
            "PAYMENT" to """{"state":"CREATED","orderId":"ORDER-123","recordedAt":"2026-11-12T15:00:02Z"}""",
            "PAYMENT" to """{"state":"AUTHORIZED","orderId":"ORDER-123","authorizationId":"AUTH-123","recordedAt":"2026-11-12T15:00:03Z"}""",
        )
        val payments = pledgeTrace(history).payments
        assertEquals(listOf("Pledge started", "PayPal order created", "Payment checkpoint updated", "Authorized hold"),
            payments.map { it.label })
        assertEquals(history.entries.map { it.hash }, payments.map { it.hash })
        assertEquals(Instant.parse("2026-11-12T15:00:02Z"), payments[2].recordedAt)
        assertEquals(listOf(TraceReference("PayPal order", "ORDER-123")), payments[2].references)
        `when`(records.findPledgeByToken(token, pledgeId)).thenReturn(history)
        val html = mvc.get("/w/$token/pledges/$pledgeId/trace").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertEquals(1, Regex("PayPal order created").findAll(html).count())
        listOf("Payment checkpoint updated", "hash-1", "hash-2", "hash-3", "hash-4").forEach { assertContains(html, it) }
        assertEquals(4, history.entries.size)
        val changedOrder = record(
            "PAYMENT" to """{"state":"CREATED","orderId":"ORDER-123"}""",
            "PAYMENT" to """{"state":"CREATED","orderId":"ORDER-456"}""",
        )
        assertEquals(listOf("PayPal order created", "PayPal order created"), pledgeTrace(changedOrder).payments.map { it.label })
    }

    @Test
    fun `latest completed reading renders its own evidence without combining original analysis metadata`() {
        val record = record(
            "VERDICT" to """{"verdict":"CONDITIONAL","reason":"Original reason","model":"original-model","analysisRunId":"original-run","inputHash":"original-input","readingAgreement":{"needsYourEyes":true},"review":{"verdict":"COMPATIBLE","source":"MAKER","reason":"Original review","revision":1}}""",
            "READING" to """{"availability":"PENDING","scope":"FORMAT_ASSESSMENT","attempts":0,"retryAllowed":true}""",
            "READING" to """{"availability":"AVAILABLE","scope":"FORMAT_ASSESSMENT","assessmentId":"assessment-1","assessmentHash":"assessment-hash","inputHash":"reading-input","kind":"REQUEST","verdict":"COMPATIBLE","quote":"A blue postcard","reason":"The postcard format matches.","model":"reading-model","completedAt":"2026-11-12T15:01:02.123456Z","attempts":1,"retryAllowed":false}""",
        )
        val reading = assertNotNull(pledgeTrace(record).reading)
        assertEquals("COMPATIBLE", reading.verdict)
        assertEquals("assessment-1", reading.assessmentId)
        assertEquals(Instant.parse("2026-11-12T15:01:02.123456Z"), reading.completedAt)
        assertFalse(reading.retryAllowed)
        `when`(records.findPledgeByToken(token, pledgeId)).thenReturn(record)
        val html = mvc.get("/w/$token/pledges/$pledgeId/trace").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        listOf("A blue postcard", "The postcard format matches.", "reading-model", "reading-input", "assessment-1", "assessment-hash",
            "2026-11-12T15:01:02.123456Z", "product format only", "does not verify price, finish, or delivery terms")
            .forEach { assertContains(html, it) }
        listOf("Original reason", "original-model", "Original review", "The two readings differed.", "Reading pending", "Try reading again")
            .forEach { assertFalse(html.contains(it), it) }
    }

    @Test
    fun `pending reading exposes a protected retry and stops at the two attempt limit`() {
        val pending = record(
            "VERDICT" to """{"verdict":"COMPATIBLE","model":"old-model","reason":"Old reading reason","analysisRunId":"old-run"}""",
            "READING" to """{"availability":"PENDING","scope":"FORMAT_ASSESSMENT","failureReason":"PROVIDER_UNAVAILABLE","attempts":1,"retryAllowed":true}""",
        )
        val reading = assertNotNull(pledgeTrace(pending).reading)
        assertNull(reading.verdict)
        assertNull(reading.model)
        assertNull(reading.completedAt)
        `when`(records.findPledgeByToken(token, pledgeId)).thenReturn(pending)
        val html = mvc.get("/w/$token/pledges/$pledgeId/trace").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        listOf("Reading pending", "The last attempt did not produce a usable reading.", "Try reading again",
            "action=\"/w/$token/pledges/$pledgeId/trace/reading\"", "name=\"_csrf\"")
            .forEach { assertContains(html, it) }
        listOf("old-model", "old-run", "Old reading reason").forEach { assertFalse(html.contains(it), it) }
        val capped = record("READING" to """{"availability":"PENDING","scope":"FORMAT_ASSESSMENT","attempts":2,"retryAllowed":false}""")
        assertFalse(assertNotNull(pledgeTrace(capped).reading).retryAllowed)
        `when`(records.findPledgeByToken(token, pledgeId)).thenReturn(capped)
        val cappedHtml = mvc.get("/w/$token/pledges/$pledgeId/trace").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertContains(cappedHtml, "Reading not completed")
        assertContains(cappedHtml, "no retries remain. No automatic retry will run.")
        assertFalse(cappedHtml.contains("Reading pending"))
        assertFalse(cappedHtml.contains("trace/reading"))
    }

    @Test
    fun `changed record hides later reading and preserves original evidence with integrity issue`() {
        val changed = record(
            "VERDICT" to """{"verdict":"CONDITIONAL","reason":"Original recorded reason"}""",
            "READING" to """{"availability":"AVAILABLE","scope":"FORMAT_ASSESSMENT","verdict":"COMPATIBLE","reason":"Untrusted later reason","model":"untrusted-model","attempts":1}""",
        ).copy(integrity = "CHANGED", issues = listOf("Assessment source differs from its snapshot."))
        val trace = pledgeTrace(changed)
        assertNull(trace.reading)
        assertEquals("CONDITIONAL", trace.verdict)
        `when`(records.findPledgeByToken(token, pledgeId)).thenReturn(changed)
        val html = mvc.get("/w/$token/pledges/$pledgeId/trace").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        listOf("The record has an integrity issue.", "Original recorded reason", "Assessment source differs from its snapshot.")
            .forEach { assertContains(html, it) }
        listOf("Untrusted later reason", "untrusted-model", "trace/reading").forEach { assertFalse(html.contains(it), it) }
    }

    @Test
    fun `unknown workspace pledge and invalid identifiers do not expose a trace`() {
        mvc.get("/w/$token/pledges/$pledgeId/trace").andExpect { status { isNotFound() } }
        mvc.get("/w/$token/pledges/not-a-uuid/trace").andExpect { status { isNotFound() } }
    }

    private fun record(vararg entries: Pair<String, String>) = PledgeDecisionView(
        pledgeId, UUID.randomUUID(), UUID.randomUUID(), "INTACT", emptyList(), entries.size,
        "hash-${entries.size}", entries.mapIndexed { index, (kind, payload) ->
            DecisionEntryView(index + 1, kind, mapper.readTree(payload), null, "hash-${index + 1}")
        },
    )
}

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
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertFalse
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
        val noWish = record("WISH" to """{"traceScope":"OFFER_EVIDENCE","supportingWishIds":["first","second"]}""")
        `when`(records.findPledgeByToken(token, pledgeId)).thenReturn(noWish)
        val html = mvc.get("/w/$token/pledges/$pledgeId/trace").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertContains(html, "No wish is linked to this pledge.")
        assertContains(html, "offer&#39;s recorded evidence set")
        assertContains(html, "The earlier offer snapshot is unavailable.")
        assertNull(pledgeTrace(noWish).wish)
        val legacy = record("PAYMENT" to """{"state":"CAPTURED","createdAt":"2026-01-01T00:00:00Z"}""")
        assertNull(pledgeTrace(legacy).payments.single().recordedAt)
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

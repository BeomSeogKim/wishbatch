package app.campaign.trail

import app.campaign.books.BooksCheck
import app.campaign.books.BooksMatch
import app.campaign.books.BooksPledgeRow
import app.campaign.books.BooksPledgeSnapshot
import app.campaign.books.BooksResource
import app.campaign.books.BooksResourceKind
import app.campaign.model.PledgeState
import app.campaign.port.PaymentStatus
import app.campaign.model.Campaign
import app.campaign.model.Event
import app.campaign.model.Offer
import app.campaign.model.Pledge
import app.campaign.model.Workspace
import app.campaign.store.CampaignRepository
import app.campaign.store.EventRepository
import app.campaign.store.OfferRepository
import app.campaign.store.PledgeRepository
import app.campaign.store.WorkspaceRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class MoneyTrailWebTests @Autowired constructor(
    private val mvc: MockMvc,
    private val workspaces: WorkspaceRepository,
    private val offers: OfferRepository,
    private val campaigns: CampaignRepository,
    private val pledges: PledgeRepository,
    private val events: EventRepository,
    private val mapper: JsonMapper,
) {
    @Test
    fun `persisted repeated refund transitions appear in order in table and csv`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val owner = workspaces.save(Workspace(UUID.randomUUID().toString().replace("-", "") + "12345678901", now))
        val offer = offers.save(Offer(owner.id, UUID.randomUUID(), UUID.randomUUID(), "Offer", "Spec", emptyMap(), 650, "USD", "Delivery", "US", 1, 5))
        val campaign = campaigns.save(Campaign(offer.id))
        val pledge = pledges.save(Pledge(campaign.id, 1, 650, "USD", createdAt = now, refundId = "refund-1"))
        events.save(Event(campaign.id, "REFUND_REQUEST", pledge.id, paypalId = "request-only", createdAt = now))
        listOf("CAPTURED -> REFUND_PENDING", "REFUND_PENDING -> REFUND_FAILED", "REFUND_FAILED -> REFUND_PENDING")
            .forEachIndexed { index, transition ->
                events.save(Event(campaign.id, "PLEDGE_STATE", pledge.id, detail = transition, createdAt = now.plusSeconds(index.toLong() + 1)))
            }
        val path = "/w/${owner.token}/maker/campaigns/${campaign.id}/trail"
        val html = mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertTrue(html.contains("class=\"table-scroll\" data-comparison-fallback tabindex=\"0\""))
        assertTrue(html.contains("aria-label=\"Recorded payment checkpoints table\""))
        assertTrue(html.contains("data-comparison-table"))
        assertTrue(html.contains("data-money-trail"))
        val csv = mvc.get("$path.csv").andExpect { status { isOk() } }.andReturn().response.contentAsString
        for (output in listOf(html, csv)) {
            val first = output.indexOf("Refund pending")
            val failed = output.indexOf("Refund failed", first + 1)
            val second = output.indexOf("Refund pending", failed + 1)
            assertTrue(first >= 0 && failed > first && second > failed)
            assertFalse(output.contains("request-only"))
        }
    }

    @Test
    fun `ledger uses persisted checkpoints and rejects another workspace`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val owner = workspaces.save(Workspace(UUID.randomUUID().toString().replace("-", "") + "12345678901", now))
        val other = workspaces.save(Workspace(UUID.randomUUID().toString().replace("-", "") + "12345678901", now))
        val offer = offers.save(Offer(owner.id, UUID.randomUUID(), UUID.randomUUID(), "Offer", "Spec", emptyMap(), 650, "USD", "Delivery", "US", 1, 5))
        val campaign = campaigns.save(Campaign(offer.id))
        val pledge = pledges.save(Pledge(campaign.id, 1, 650, "USD", createdAt = now, captureId = "cap-1"))
        events.save(Event(campaign.id, "PLEDGE_STATE", pledge.id, detail = "CAPTURE_PENDING -> CAPTURED", createdAt = now))
        val otherPledge = pledges.save(Pledge(campaign.id, 1, 650, "USD", createdAt = now, captureId = "other-capture"))
        events.save(Event(campaign.id, "PLEDGE_STATE", otherPledge.id, detail = "CAPTURE_PENDING -> CAPTURED", createdAt = now))
        val fanPath = "/w/${owner.token}/pledges/${pledge.id}"
        mvc.get(fanPath).andExpect {
            status { isOk() }
            content {
                string(org.hamcrest.Matchers.containsString("cap-1"))
                string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("other-capture")))
                string(org.hamcrest.Matchers.containsString("local recording checkpoints"))
            }
        }
        mvc.get("/w/${other.token}/pledges/${pledge.id}").andExpect { status { isNotFound() } }
        val snapshot = BooksPledgeSnapshot(PledgeState.CAPTURED, listOf(
            BooksResource(BooksResourceKind.CAPTURE, "=cap-1", PaymentStatus.COMPLETED, "COMPLETED", 650, "USD", true),
        ), BooksMatch.MATCH)
        val oldId = UUID.randomUUID()
        val oldSnapshot = BooksPledgeSnapshot(PledgeState.AUTHORIZED, listOf(
            BooksResource(BooksResourceKind.AUTHORIZATION, "old-auth", PaymentStatus.CREATED, "CREATED", 650, "USD", true),
        ), BooksMatch.MISMATCH)
        events.save(Event(campaign.id, "BOOKS_CHECK", detail = mapper.writeValueAsString(
            BooksCheck(oldId, campaign.id, now.minusSeconds(1), now.minusSeconds(1),
                listOf(BooksPledgeRow(pledge.id, 1, 650, "USD", oldSnapshot, oldSnapshot))),
        ), createdAt = now.minusSeconds(1), id = oldId))
        val checkId = UUID.randomUUID()
        val check = events.save(Event(campaign.id, "BOOKS_CHECK", detail = mapper.writeValueAsString(
            BooksCheck(checkId, campaign.id, now, now, listOf(BooksPledgeRow(pledge.id, 1, 650, "USD", snapshot, snapshot))),
        ), createdAt = now, id = checkId))
        val path = "/w/${owner.token}/maker/campaigns/${campaign.id}/trail"
        mvc.get(path).andExpect { status { isOk() }; content { string(org.hamcrest.Matchers.containsString("Payment confirmed")) }; content { string(org.hamcrest.Matchers.containsString("<table")) } }
        mvc.get(path).andExpect { content { string(org.hamcrest.Matchers.containsString("checkId=${check.id}")); string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-json-marker"))) } }
        mvc.get(path).andExpect { content { string(org.hamcrest.Matchers.containsString("CAPTURE: =cap-1")); string(org.hamcrest.Matchers.containsString("MATCH")); string(org.hamcrest.Matchers.containsString("Not checked")); string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("old-auth"))) } }
        mvc.get("/w/${owner.token}/maker/campaigns/${campaign.id}/comparison.csv").andExpect {
            status { isOk() }; content { string(org.hamcrest.Matchers.containsString("CAPTURE: =cap-1")) }
        }
        mvc.get("/w/${other.token}/maker/campaigns/${campaign.id}/comparison.csv").andExpect { status { isNotFound() } }
        mvc.get("$path.csv").andExpect { status { isOk() }; content { string(org.hamcrest.Matchers.containsString("cap-1")) } }
        mvc.get("/w/${other.token}/maker/campaigns/${campaign.id}/trail.csv").andExpect { status { isNotFound() } }
    }
}

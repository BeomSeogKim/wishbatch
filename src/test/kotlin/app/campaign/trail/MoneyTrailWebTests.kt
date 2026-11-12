package app.campaign.trail

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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
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
) {
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
        val check = events.save(Event(campaign.id, "BOOKS_CHECK", detail = "private-json-marker", createdAt = now))
        val path = "/w/${owner.token}/maker/campaigns/${campaign.id}/trail"
        mvc.get(path).andExpect { status { isOk() }; content { string(org.hamcrest.Matchers.containsString("Payment confirmed")) }; content { string(org.hamcrest.Matchers.containsString("<table")) } }
        mvc.get(path).andExpect { content { string(org.hamcrest.Matchers.containsString("checkId=${check.id}")); string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-json-marker"))) } }
        mvc.get("$path.csv").andExpect { status { isOk() }; content { string(org.hamcrest.Matchers.containsString("cap-1")) } }
        mvc.get("/w/${other.token}/maker/campaigns/${campaign.id}/trail.csv").andExpect { status { isNotFound() } }
    }
}

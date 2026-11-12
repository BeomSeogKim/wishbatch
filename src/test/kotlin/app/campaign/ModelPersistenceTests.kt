package app.campaign

import app.campaign.model.AnalysisRun
import app.campaign.model.Campaign
import app.campaign.model.CampaignReason
import app.campaign.model.CampaignState
import app.campaign.model.Event
import app.campaign.model.Offer
import app.campaign.model.Option
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.model.ShippingAddress
import app.campaign.model.Wish
import app.campaign.model.WishSource
import app.campaign.model.Workspace
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class ModelPersistenceTests @Autowired constructor(private val entityManager: EntityManager) {
    private val createdAt = Instant.parse("2026-01-02T03:04:05.123456Z")
    private val largeAmount = 9_007_199_254_740_993L

    @Test
    fun `all entities preserve exact amounts timestamps references and structured values`() {
        val workspace = Workspace(token = "test-workspace", createdAt = createdAt)
        val option = Option(
            workspaceId = workspace.id,
            name = "Landscape print",
            format = "print",
            fixedAttributes = mapOf("size" to "A5", "subject" to "Mountains, \"Lake\"\nForest"),
            allowedVariants = mapOf("finish" to listOf("matte", "glossy"), "border" to listOf("white")),
            unitCost = largeAmount,
            currency = "USD",
            minBatch = 5,
            maxCapacity = 20,
            leadTimeText = "Ships in two weeks",
            shipRegion = "US",
        )
        val wish = Wish(
            workspaceId = workspace.id,
            text = "A matte mountain print would be lovely.",
            source = WishSource.IMPORTED,
            displayName = "Sample buyer",
            createdAt = createdAt,
            hidden = true,
        )
        val analysis = AnalysisRun(
            workspaceId = workspace.id,
            model = "test-model",
            inputHash = "synthetic-input-hash",
            resultJson = "{\"proposal\":null,\"text\":\"${"x".repeat(600)}\"}",
            validationFlags = listOf("UNKNOWN_REFERENCE", "UNVERIFIED_QUOTE"),
            isSeed = true,
            createdAt = createdAt,
        )
        val offer = Offer(
            workspaceId = workspace.id,
            optionId = option.id,
            analysisRunId = analysis.id,
            title = "Mountain print",
            specText = "A5 mountain print on archival paper.",
            chosenVariants = mapOf("finish" to "matte", "border" to "white"),
            unitPrice = largeAmount,
            currency = "USD",
            deliveryTerms = "Shipping is included.",
            shipRegion = "US",
            minBatch = 5,
            capacity = 20,
            maxQtyPerPledge = 2,
            supportingWishIds = listOf(wish.id),
        )
        val campaign = Campaign(
            offerId = offer.id,
            state = CampaignState.NOT_FUNDED,
            reason = CampaignReason.CAPTURE_SHORTFALL,
            openedAt = createdAt,
            closesAt = createdAt.plusSeconds(900),
            confirmDeadline = createdAt.plusSeconds(44_100),
            needsAttention = true,
        )
        val address = ShippingAddress(
            fullName = "Sample Buyer",
            addressLine1 = "100 Example Street",
            addressLine2 = "Unit 2",
            adminArea1 = "CA",
            adminArea2 = "Example City",
            postalCode = "90001",
            countryCode = "US",
        )
        val pledgeId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val pledge = Pledge(
            campaignId = campaign.id,
            qty = 1,
            amount = largeAmount,
            currency = "USD",
            state = PledgeState.REFUND_PENDING,
            paypalOrderId = "order-test",
            authorizationId = "authorization-test",
            authorizationExpiresAt = createdAt.plusSeconds(86_400),
            captureId = "capture-test",
            refundId = "refund-test",
            payerEmailMasked = "s***@example.test",
            shippingAddress = address,
            lastError = "Synthetic pending refund",
            createdAt = createdAt,
            id = pledgeId,
        )
        val event = Event(
            campaignId = campaign.id,
            pledgeId = pledge.id,
            type = "REFUND_PENDING",
            paypalEndpoint = "/v2/payments/captures/capture-test/refund",
            paypalId = pledge.refundId,
            paypalStatus = "PENDING",
            debugId = "debug-test",
            detail = "Awaiting provider completion.",
            createdAt = createdAt,
        )
        listOf(workspace, option, wish, analysis, offer, campaign, pledge, event).forEach(entityManager::persist)
        flushAndClear()

        val savedWorkspace = entityManager.find(Workspace::class.java, workspace.id)
        assertEquals(workspace.token, savedWorkspace.token)
        assertEquals(createdAt, savedWorkspace.createdAt)
        val savedOption = entityManager.find(Option::class.java, option.id)
        assertEquals(workspace.id, savedOption.workspaceId)
        assertEquals(option.fixedAttributes, savedOption.fixedAttributes)
        assertEquals(option.allowedVariants, savedOption.allowedVariants)
        assertEquals(largeAmount, savedOption.unitCost)
        assertEquals("USD", savedOption.currency)
        assertEquals(option.leadTimeText, savedOption.leadTimeText)
        val savedWish = entityManager.find(Wish::class.java, wish.id)
        assertEquals(wish.text, savedWish.text)
        assertEquals(WishSource.IMPORTED, savedWish.source)
        assertEquals(wish.displayName, savedWish.displayName)
        assertTrue(savedWish.hidden)
        assertEquals(createdAt, savedWish.createdAt)
        val savedAnalysis = entityManager.find(AnalysisRun::class.java, analysis.id)
        assertEquals(analysis.model, savedAnalysis.model)
        assertEquals(analysis.inputHash, savedAnalysis.inputHash)
        assertEquals(analysis.resultJson, savedAnalysis.resultJson)
        assertEquals(analysis.validationFlags, savedAnalysis.validationFlags)
        assertTrue(savedAnalysis.isSeed)
        assertEquals(createdAt, savedAnalysis.createdAt)
        val savedOffer = entityManager.find(Offer::class.java, offer.id)
        assertEquals(option.id, savedOffer.optionId)
        assertEquals(analysis.id, savedOffer.analysisRunId)
        assertEquals(offer.chosenVariants, savedOffer.chosenVariants)
        assertEquals(listOf(wish.id), savedOffer.supportingWishIds)
        assertEquals(largeAmount, savedOffer.unitPrice)
        assertEquals("USD", savedOffer.currency)
        assertEquals(offer.maxQtyPerPledge, savedOffer.maxQtyPerPledge)
        val savedCampaign = entityManager.find(Campaign::class.java, campaign.id)
        assertEquals(offer.id, savedCampaign.offerId)
        assertEquals(CampaignState.NOT_FUNDED, savedCampaign.state)
        assertEquals(CampaignReason.CAPTURE_SHORTFALL, savedCampaign.reason)
        assertEquals(createdAt, savedCampaign.openedAt)
        assertEquals(campaign.closesAt, savedCampaign.closesAt)
        assertEquals(campaign.confirmDeadline, savedCampaign.confirmDeadline)
        assertTrue(savedCampaign.needsAttention)
        val savedPledge = entityManager.find(Pledge::class.java, pledgeId)
        assertEquals(campaign.id, savedPledge.campaignId)
        assertEquals(largeAmount, savedPledge.amount)
        assertEquals("USD", savedPledge.currency)
        assertEquals(pledge.state, savedPledge.state)
        assertEquals(pledge.paypalOrderId, savedPledge.paypalOrderId)
        assertEquals(pledge.authorizationId, savedPledge.authorizationId)
        assertEquals(pledge.authorizationExpiresAt, savedPledge.authorizationExpiresAt)
        assertEquals(pledge.captureId, savedPledge.captureId)
        assertEquals(pledge.refundId, savedPledge.refundId)
        assertEquals(pledge.payerEmailMasked, savedPledge.payerEmailMasked)
        assertEquals(address, savedPledge.shippingAddress)
        assertEquals(pledge.lastError, savedPledge.lastError)
        assertEquals(createdAt, savedPledge.createdAt)
        val savedEvent = entityManager.find(Event::class.java, event.id)
        assertEquals(campaign.id, savedEvent.campaignId)
        assertEquals(pledgeId, savedEvent.pledgeId)
        assertEquals(event.type, savedEvent.type)
        assertEquals(event.paypalEndpoint, savedEvent.paypalEndpoint)
        assertEquals(event.paypalId, savedEvent.paypalId)
        assertEquals(event.paypalStatus, savedEvent.paypalStatus)
        assertEquals(event.debugId, savedEvent.debugId)
        assertEquals(event.detail, savedEvent.detail)
        assertEquals(createdAt, savedEvent.createdAt)
    }

    @Test
    fun `draft and unapproved records retain absent payment and timing values`() {
        val workspace = Workspace(token = "minimal-workspace", createdAt = createdAt)
        val wish = Wish(workspace.id, "A print please", WishSource.ONSITE, createdAt = createdAt)
        val analysis = AnalysisRun(workspace.id, "test-model", "hash", "{}", createdAt = createdAt)
        val campaign = Campaign(offerId = UUID.randomUUID())
        val pledge = Pledge(campaign.id, 1, 100L, "USD", createdAt = createdAt)
        val event = Event(campaign.id, "DRAFT_CREATED", createdAt = createdAt)
        listOf(workspace, wish, analysis, campaign, pledge, event).forEach(entityManager::persist)
        flushAndClear()

        val savedWish = entityManager.find(Wish::class.java, wish.id)
        assertNull(savedWish.displayName)
        assertFalse(savedWish.hidden)
        assertEquals(WishSource.ONSITE, savedWish.source)
        assertEquals(emptyList(), entityManager.find(AnalysisRun::class.java, analysis.id).validationFlags)
        val savedCampaign = entityManager.find(Campaign::class.java, campaign.id)
        assertEquals(CampaignState.DRAFT, savedCampaign.state)
        assertNull(savedCampaign.reason)
        assertNull(savedCampaign.openedAt)
        assertNull(savedCampaign.closesAt)
        assertNull(savedCampaign.confirmDeadline)
        assertFalse(savedCampaign.needsAttention)
        val savedPledge = entityManager.find(Pledge::class.java, pledge.id)
        assertEquals(PledgeState.CREATED, savedPledge.state)
        assertNull(savedPledge.paypalOrderId)
        assertNull(savedPledge.authorizationId)
        assertNull(savedPledge.authorizationExpiresAt)
        assertNull(savedPledge.captureId)
        assertNull(savedPledge.refundId)
        assertNull(savedPledge.payerEmailMasked)
        assertNull(savedPledge.shippingAddress)
        assertNull(savedPledge.lastError)
        val savedEvent = entityManager.find(Event::class.java, event.id)
        assertNull(savedEvent.pledgeId)
        assertNull(savedEvent.paypalEndpoint)
        assertNull(savedEvent.paypalId)
        assertNull(savedEvent.paypalStatus)
        assertNull(savedEvent.debugId)
        assertNull(savedEvent.detail)
    }

    @Test
    fun `every state and campaign reason survives an update without ordinal mapping`() {
        var campaign = Campaign(offerId = UUID.randomUUID())
        var pledge = Pledge(campaign.id, 1, 100L, "USD", createdAt = createdAt)
        entityManager.persist(campaign)
        entityManager.persist(pledge)
        for (state in CampaignState.entries) {
            campaign.state = state
            flushAndClear()
            campaign = entityManager.find(Campaign::class.java, campaign.id)
            pledge = entityManager.find(Pledge::class.java, pledge.id)
            assertEquals(state, campaign.state)
            assertEquals(state.name, entityManager.createNativeQuery("select state from campaigns").singleResult)
        }
        for (reason in CampaignReason.entries) {
            campaign.reason = reason
            flushAndClear()
            campaign = entityManager.find(Campaign::class.java, campaign.id)
            pledge = entityManager.find(Pledge::class.java, pledge.id)
            assertEquals(reason, campaign.reason)
        }
        for (state in PledgeState.entries) {
            pledge.state = state
            flushAndClear()
            pledge = entityManager.find(Pledge::class.java, pledge.id)
            assertEquals(state, pledge.state)
            assertEquals(state.name, entityManager.createNativeQuery("select state from pledges").singleResult)
        }
    }

    private fun flushAndClear() {
        entityManager.flush()
        entityManager.clear()
    }
}

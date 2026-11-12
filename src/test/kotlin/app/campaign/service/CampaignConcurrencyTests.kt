package app.campaign.service

import app.campaign.model.AnalysisRun
import app.campaign.model.Campaign
import app.campaign.model.CampaignState
import app.campaign.model.Money
import app.campaign.model.Offer
import app.campaign.model.Option
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.model.Workspace
import app.campaign.provenance.DecisionRecordService
import app.campaign.port.AuthorizationDetails
import app.campaign.port.AuthorizationId
import app.campaign.port.CaptureDetails
import app.campaign.port.CaptureId
import app.campaign.port.PaymentResult
import app.campaign.port.PaymentStatus
import app.campaign.port.VoidDetails
import app.campaign.rules.PaymentAction
import app.campaign.rules.idempotencyKey
import app.campaign.store.AnalysisRunRepository
import app.campaign.store.CampaignRepository
import app.campaign.store.EventRepository
import app.campaign.store.OfferRepository
import app.campaign.store.OptionRepository
import app.campaign.store.PledgeRepository
import app.campaign.store.WorkspaceRepository
import app.testing.PaymentCall
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(CampaignServiceTests.PaymentTestConfiguration::class)
class CampaignConcurrencyTests @Autowired constructor(
    private val service: CampaignService,
    private val workspaces: WorkspaceRepository,
    private val options: OptionRepository,
    private val analyses: AnalysisRunRepository,
    private val offers: OfferRepository,
    private val campaigns: CampaignRepository,
    private val pledges: PledgeRepository,
    private val events: EventRepository,
    private val gateway: CrashableGateway,
    private val clock: MutableClock,
    private val persistence: CampaignPersistence,
    private val records: DecisionRecordService,
) {
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val ownedWorkspaces = mutableListOf<UUID>()

    @BeforeEach
    fun resetGateway() {
        gateway.reset()
        clock.current = start
    }

    @AfterEach
    fun cleanFixtures() {
        ownedWorkspaces.forEach { workspaceId ->
            val workspaceOffers = offers.findByWorkspaceId(workspaceId)
            campaigns.findByOfferIdIn(workspaceOffers.map { it.id }).forEach { campaign ->
                events.deleteAll(events.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id))
                pledges.deleteAll(pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id))
                campaigns.delete(campaign)
            }
            offers.deleteAll(workspaceOffers)
            analyses.deleteAll(analyses.findByWorkspaceId(workspaceId))
            options.deleteAll(options.findByWorkspaceId(workspaceId))
            workspaces.deleteById(workspaceId)
        }
    }

    @Test
    fun `overlapping approvals reserve only the available capacity and release the losing hold`() {
        val ref = fixture(capacity = 1)
        val requests = List(2) { pledge(ref, PledgeState.CREATED) }
        listOf("first-hold", "second-hold").forEach { authorizationId ->
            gateway.fake.authorizeOrderResults.add(PaymentResult.Received(
                AuthorizationDetails(AuthorizationId(authorizationId), Money(200, "USD"), null),
                PaymentStatus.CREATED,
            ))
        }
        gateway.fake.voidAuthorizationResults.add(PaymentResult.Received(
            VoidDetails(AuthorizationId("second-hold")), PaymentStatus.VOIDED,
        ))
        gateway.fake.readAuthorizationResults.add(PaymentResult.Received(
            AuthorizationDetails(AuthorizationId("first-hold"), Money(200, "USD"), null), PaymentStatus.CREATED,
        ))

        val results = overlapping(gatewayHook = { gateway.afterAuthorization = it }) { index ->
            service.completePledge(PledgeRef(ref, requests[index].id))
        }

        assertEquals(1, results.count { it is ServiceResult.Success })
        val rejected = assertIs<ServiceResult.Rejected>(results.single { it is ServiceResult.Rejected })
        assertEquals(Rejection.CAPACITY_EXCEEDED, rejected.reason)
        assertEquals(PledgeState.VOIDED, rejected.pledge?.state)
        val saved = pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(ref.campaignId)
        assertEquals(1, saved.count { it.state == PledgeState.AUTHORIZED })
        assertEquals(1, saved.count { it.state == PledgeState.VOIDED })
        assertEquals(2, gateway.fake.calls.filterIsInstance<PaymentCall.AuthorizeOrder>().size)
        assertEquals(1, gateway.fake.calls.filterIsInstance<PaymentCall.VoidAuthorization>().size)
        val view = success(service.readCampaign(ref))
        assertEquals(1L, view.counts.authorizedHolds)
        assertEquals(0L, view.capacityLeft)
        assertEquals(0L, view.counts.paid)
    }

    @Test
    fun `overlapping production confirmations capture once and publish one funded outcome`() {
        val ref = fixture()
        val pledge = pledge(ref, PledgeState.AUTHORIZED)
        gateway.fake.captureResults.add(PaymentResult.Received(
            CaptureDetails(CaptureId("completed-capture"), Money(200, "USD"), AuthorizationId(pledge.authorizationId!!)),
            PaymentStatus.COMPLETED,
        ))

        val results = overlapping(gatewayHook = { gateway.beforeCapture = it }) {
            service.confirmProduction(ref)
        }

        results.forEach { result ->
            val view = success(result)
            assertEquals(CampaignState.FUNDED, view.state)
            assertEquals(1L, view.counts.paid)
        }
        val capture = gateway.fake.calls.filterIsInstance<PaymentCall.Capture>().single()
        assertEquals(idempotencyKey(pledge.id, PaymentAction.CAPTURE), capture.idempotencyKey)
        assertEquals(PledgeState.CAPTURED, pledges.findById(pledge.id).orElseThrow().state)
        val transitions = events.findByCampaignIdOrderByCreatedAtAscIdAsc(ref.campaignId)
            .filter { it.type == "CAMPAIGN_STATE" }
        assertEquals(listOf("OPEN -> SETTLING", "SETTLING -> FUNDED").sorted(),
            transitions.map { it.detail }.sortedBy { it })
    }

    @Test
    fun `overlapping approval submissions authorize the same pledge only once`() {
        val ref = fixture()
        val pledge = pledge(ref, PledgeState.CREATED)
        gateway.fake.authorizeOrderResults.add(PaymentResult.Received(
            AuthorizationDetails(AuthorizationId("single-hold"), Money(200, "USD"), null),
            PaymentStatus.CREATED,
        ))
        gateway.fake.readAuthorizationResults.add(PaymentResult.Received(
            AuthorizationDetails(AuthorizationId("single-hold"), Money(200, "USD"), null),
            PaymentStatus.CREATED,
        ))

        val results = overlapping(gatewayHook = { gateway.afterAuthorization = it }) {
            service.completePledge(PledgeRef(ref, pledge.id))
        }

        results.forEach { result ->
            val view = success(result)
            assertEquals(pledge.id, view.id)
            assertEquals(PledgeState.AUTHORIZED, view.state)
        }
        val authorization = gateway.fake.calls.filterIsInstance<PaymentCall.AuthorizeOrder>().single()
        assertEquals(idempotencyKey(pledge.id, PaymentAction.AUTHORIZE), authorization.idempotencyKey)
        assertEquals(1, pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(ref.campaignId).size)
        val view = success(service.readCampaign(ref))
        assertEquals(1L, view.counts.authorizedHolds)
        assertEquals(0L, view.counts.paid)
    }

    private fun fixture(capacity: Int = 3): CampaignRef {
        val workspace = workspaces.saveAndFlush(Workspace("test-${UUID.randomUUID()}", start))
        ownedWorkspaces.add(workspace.id)
        val option = options.saveAndFlush(Option(workspace.id, "Print", "print", emptyMap(), emptyMap(),
            100, "USD", 1, capacity, "Two weeks", "US"))
        val analysis = analyses.saveAndFlush(AnalysisRun(workspace.id, "synthetic", "hash", "{}", createdAt = start))
        val offer = offers.saveAndFlush(Offer(workspace.id, option.id, analysis.id, "Print", "A5 print", emptyMap(),
            200, "USD", "Shipping included", "US", 1, capacity, minOf(3, capacity)))
        val campaign = campaigns.saveAndFlush(Campaign(offer.id, CampaignState.OPEN,
            openedAt = start, closesAt = start.plusSeconds(900), confirmDeadline = start.plusSeconds(44_100)))
        persistence.checkpoint { records.recordOffer(offer) }
        return CampaignRef(workspace.id, campaign.id)
    }

    private fun pledge(ref: CampaignRef, state: PledgeState): Pledge {
        val id = UUID.randomUUID()
        return pledges.saveAndFlush(Pledge(ref.campaignId, 1, 200, "USD", state,
            paypalOrderId = "order-$id",
            authorizationId = if (state == PledgeState.AUTHORIZED) "authorization-$id" else null,
            authorizationExpiresAt = if (state == PledgeState.AUTHORIZED) start.plusSeconds(86_400) else null,
            createdAt = start, id = id)).also(gateway::registerOrder)
    }

    private fun <T> overlapping(gatewayHook: ((() -> Unit) -> Unit), action: (Int) -> T): List<T> {
        val executor = Executors.newFixedThreadPool(2)
        val begin = CyclicBarrier(3)
        val providerEntered = CountDownLatch(1)
        val releaseProvider = CountDownLatch(1)
        gatewayHook {
            providerEntered.countDown()
            assertTrue(releaseProvider.await(5, TimeUnit.SECONDS))
        }
        try {
            val tasks = List(2) { index ->
                executor.submit<T> {
                    begin.await(5, TimeUnit.SECONDS)
                    action(index)
                }
            }
            begin.await(5, TimeUnit.SECONDS)
            assertTrue(providerEntered.await(5, TimeUnit.SECONDS))
            releaseProvider.countDown()
            return tasks.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            releaseProvider.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun <T> success(result: ServiceResult<T>): T = assertIs<ServiceResult.Success<T>>(result).value
}

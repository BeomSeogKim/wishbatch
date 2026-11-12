package app.campaign.provenance

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.model.CampaignState
import app.campaign.model.Money
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.model.ShippingAddress
import app.campaign.model.Workspace
import app.campaign.port.AuthorizationDetails
import app.campaign.port.AuthorizationId
import app.campaign.port.CaptureDetails
import app.campaign.port.CaptureId
import app.campaign.port.OrderDetails
import app.campaign.port.OrderId
import app.campaign.port.PaymentGateway
import app.campaign.port.PaymentResult
import app.campaign.port.PaymentStatus
import app.campaign.port.RefundDetails
import app.campaign.port.RefundId
import app.campaign.port.VoidDetails
import app.campaign.service.CampaignPayments
import app.campaign.service.CampaignPersistence
import app.campaign.service.CampaignRef
import app.campaign.service.CampaignService
import app.campaign.service.CampaignServiceTests
import app.campaign.service.CrashableGateway
import app.campaign.service.DraftOffer
import app.campaign.service.MutableClock
import app.campaign.service.PledgeRef
import app.campaign.service.ServiceResult
import app.campaign.service.SimulatedProcessCrash
import app.campaign.store.EventRepository
import app.campaign.store.PledgeRepository
import app.campaign.workspace.WorkspaceService
import app.testing.PaymentCall
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(CampaignServiceTests.PaymentTestConfiguration::class)
class ProvenanceLifecycleTests @Autowired constructor(
    private val workspaces: WorkspaceService,
    private val runs: AnalysisRunService,
    private val service: CampaignService,
    private val payments: CampaignPayments,
    private val persistence: CampaignPersistence,
    private val pledges: PledgeRepository,
    private val events: EventRepository,
    private val records: DecisionRecordService,
    private val query: DecisionRecordQuery,
    private val entities: EntityManager,
    private val mapper: JsonMapper,
    private val gateway: CrashableGateway,
    private val gateways: ObjectProvider<PaymentGateway>,
    private val clock: MutableClock,
) {
    private val owned = mutableListOf<UUID>()

    @BeforeEach
    fun reset() {
        gateway.reset()
        clock.current = Instant.parse("2026-01-01T00:00:00Z")
    }

    @AfterEach
    fun cleanup() {
        owned.forEach { id -> persistence.checkpoint {
            val campaignIds = "select c.id from Campaign c where c.offerId in (select o.id from Offer o where o.workspaceId = :id)"
            listOf(
                "DecisionRecordEntry e where e.pledgeId in (select r.pledgeId from PledgeDecisionRecord r where r.workspaceId = :id)",
                "PledgeDecisionRecord r where r.workspaceId = :id", "DecisionSourceSnapshot s where s.workspaceId = :id",
                "Event e where e.campaignId in ($campaignIds)", "Pledge p where p.campaignId in ($campaignIds)",
                "Campaign c where c.id in ($campaignIds)", "Offer o where o.workspaceId = :id",
                "AnalysisRun r where r.workspaceId = :id", "Wish w where w.workspaceId = :id",
                "Option o where o.workspaceId = :id", "Workspace w where w.id = :id",
            ).forEach { entities.createQuery("delete from $it").setParameter("id", id).executeUpdate() }
        } }
    }

    @Test
    fun `published lifecycle captures immutable sources provider checkpoints and deduplicates fresh writers`() {
        val fixture = fixture()
        val pledge = authorize(fixture, 3)
        gateway.fake.captureResults.add(received(capture(pledge), PaymentStatus.COMPLETED))
        assertEquals(CampaignState.FUNDED, success(service.confirmProduction(fixture.ref)).state)
        val document = document(fixture)
        val record = document.pledges.single()
        assertEquals("INTACT", record.integrity)
        assertEquals(listOf("WISH", "VERDICT", "OFFER", "PAYMENT", "PAYMENT", "PAYMENT", "PAYMENT", "PAYMENT"),
            record.entries.map { it.kind })
        assertEquals(listOf("CREATED", "CREATED", "CREATED", "AUTHORIZED", "CAPTURED"),
            record.entries.filter { it.kind == "PAYMENT" }.map { it.payload["state"].asString() })
        assertTrue(record.entries.first { it.kind == "PAYMENT" }.payload["orderId"].isNull)
        assertEquals(pledge.paypalOrderId, record.entries[4].payload["orderId"].asString())
        assertEquals(pledge.authorizationId, record.entries[5].payload["authorizationId"].asString())
        assertEquals(capture(pledge).captureId.value, record.entries.last().payload["captureId"].asString())
        assertEquals("UNAVAILABLE", record.entries.first().payload["availability"].asString())
        assertEquals("UNAVAILABLE", record.entries[1].payload["availability"].asString())
        assertEquals("AVAILABLE", record.entries[2].payload["availability"].asString())
        val hashing = DecisionRecordHash(mapper)
        document.sources.forEach { assertEquals(hashing.sha256(hashing.canonical(it.payload)), it.hash) }
        val head = PledgeDecisionRecord(pledge.id, fixture.workspace.id, fixture.ref.campaignId, record.offerId!!)
        record.entries.forEachIndexed { index, entry ->
            assertEquals(index + 1, entry.position)
            assertEquals(record.entries.getOrNull(index - 1)?.hash, entry.previousHash)
            assertEquals(hashing.entry(head, entry.position, entry.kind, hashing.canonical(entry.payload), entry.previousHash), entry.hash)
        }
        assertEquals(record.entries.last().hash, record.lastHash)
        val exported = mapper.writeValueAsString(document)
        listOf("buyer@example.test", "b***@example.test", "Synthetic Buyer", "Synthetic Street", "payerEmail", "shippingAddress")
            .forEach { assertFalse(exported.contains(it)) }
        val calls = gateway.fake.calls.size
        success(service.confirmProduction(fixture.ref))
        success(service.completePledge(PledgeRef(fixture.ref, pledge.id)))
        val fresh = CampaignPayments(gateways, persistence, pledges, events, clock,
            PledgeProvenanceCheckpoint(persistence, records, events, clock))
        fresh.save(pledges.findById(pledge.id).orElseThrow())
        fresh.save(pledges.findById(pledge.id).orElseThrow())
        assertEquals(calls, gateway.fake.calls.size)
        repeat(2) { assertEquals(document, document(fixture)) }
    }

    @Test
    fun `wish edit preserves original evidence while cancellation records released authorization`() {
        val fixture = fixture()
        val pledge = authorize(fixture, 1)
        val before = document(fixture)
        val wish = workspaces.listWishes(fixture.workspace.token).first()
        persistence.checkpoint {
            entities.createNativeQuery("update wishes set text = :text where id = :id")
                .setParameter("text", "Changed after analysis").setParameter("id", wish.id).executeUpdate()
        }
        gateway.fake.voidAuthorizationResults.add(received(VoidDetails(AuthorizationId(pledge.authorizationId!!)), PaymentStatus.VOIDED))
        assertEquals(PledgeState.VOIDED, success(service.cancel(fixture.ref)).pledges.single().state)
        assertEquals(PledgeState.VOIDED, pledges.findById(pledge.id).orElseThrow().state)
        val after = document(fixture)
        assertEquals(before.sources, after.sources)
        assertEquals("CHANGED", after.pledges.single().integrity)
        assertEquals(before.pledges.single().entries, after.pledges.single().entries.dropLast(1))
        assertEquals("VOIDED", after.pledges.single().entries.last().payload["state"].asString())
        assertTrue(after.pledges.single().issues.any { it.startsWith("Analysis input or offer differs") })
        assertTrue(events.findByCampaignIdOrderByCreatedAtAscIdAsc(fixture.ref.campaignId).none { it.type == "PROVENANCE_RECORD_FAILED" })
    }

    @ParameterizedTest
    @ValueSource(strings = ["chain", "identity"])
    fun `corrupt provenance cannot roll back refund or prevent subsequent release`(corruption: String) {
        val fixture = fixture()
        val first = authorize(fixture, 2)
        val second = authorize(fixture, 1)
        val before = document(fixture).pledges.single { it.pledgeId == first.id }.entries
        persistence.checkpoint {
            val sql = if (corruption == "chain")
                "update decision_record_entries set hash = 'changed' where pledge_id = :id and position = 1"
            else "update pledge_decision_records set offer_id = random_uuid() where pledge_id = :id"
            entities.createNativeQuery(sql).setParameter("id", first.id).executeUpdate()
        }
        gateway.fake.captureResults.add(received(capture(first), PaymentStatus.COMPLETED))
        gateway.fake.captureResults.add(PaymentResult.Failed(PaymentStatus.DECLINED))
        gateway.fake.refundCaptureResults.add(received(RefundDetails(RefundId("refund-${first.id}"),
            Money(first.amount, first.currency), capture(first).captureId), PaymentStatus.COMPLETED))
        gateway.fake.voidAuthorizationResults.add(received(VoidDetails(AuthorizationId(second.authorizationId!!)), PaymentStatus.VOIDED))
        val result = success(service.confirmProduction(fixture.ref))
        assertEquals(CampaignState.NOT_FUNDED, result.state)
        val saved = pledges.findById(first.id).orElseThrow()
        assertEquals(PledgeState.REFUNDED, saved.state)
        assertEquals(capture(first).captureId.value, saved.captureId)
        assertEquals("refund-${first.id}", saved.refundId)
        assertEquals(PledgeState.VOIDED, pledges.findById(second.id).orElseThrow().state)
        assertEquals(1, gateway.fake.calls.filterIsInstance<PaymentCall.RefundCapture>().size)
        assertEquals(1, gateway.fake.calls.filterIsInstance<PaymentCall.VoidAuthorization>().size)
        val failures = events.findByCampaignIdOrderByCreatedAtAscIdAsc(fixture.ref.campaignId)
            .filter { it.type == "PROVENANCE_RECORD_FAILED" }
        assertTrue(failures.isNotEmpty())
        assertTrue(failures.all { it.pledgeId == first.id && it.detail == "Decision record checkpoint failed." && it.paypalId == null })
        val changed = document(fixture).pledges.single { it.pledgeId == first.id }
        assertEquals("CHANGED", changed.integrity)
        assertEquals(before.map { it.payload }, changed.entries.map { it.payload })
        val count = gateway.fake.calls.size
        success(service.reconcile(fixture.ref))
        assertEquals(count, gateway.fake.calls.size)
    }

    @Test
    fun `interrupted capture recovers provider truth without another mutation or duplicate checkpoint`() {
        val fixture = fixture()
        val pledge = authorize(fixture, 3)
        gateway.fake.captureResults.add(received(capture(pledge), PaymentStatus.COMPLETED))
        gateway.crashAfterCaptureNumber = 1
        assertFailsWith<SimulatedProcessCrash> { service.confirmProduction(fixture.ref) }
        assertEquals(PledgeState.AUTHORIZED, pledges.findById(pledge.id).orElseThrow().state)
        gateway.fake.readAuthorizationResults.add(received(AuthorizationDetails(AuthorizationId(pledge.authorizationId!!),
            Money(pledge.amount, pledge.currency), expiresAt = null, captureId = capture(pledge).captureId), PaymentStatus.CAPTURED))
        gateway.fake.readCaptureResults.add(received(capture(pledge), PaymentStatus.COMPLETED))
        assertEquals(CampaignState.FUNDED, success(service.confirmProduction(fixture.ref)).state)
        assertEquals(1, gateway.fake.calls.filterIsInstance<PaymentCall.Capture>().size)
        val saved = document(fixture).pledges.single()
        assertEquals("INTACT", saved.integrity)
        assertEquals(1, saved.entries.count { it.kind == "PAYMENT" && it.payload["state"].asString() == "CAPTURED" })
        payments.save(pledges.findById(pledge.id).orElseThrow())
        assertEquals(saved, document(fixture).pledges.single())
    }

    private fun fixture(): Fixture {
        val workspace = workspaces.create().also { owned.add(it.id) }
        val run = assertNotNull(runs.latest(workspace.token))
        val proposal = assertNotNull(run.output.proposal)
        val option = workspaces.listOptions(workspace.token).single { it.id == proposal.optionId }
        assertEquals(listOf("ANALYSIS"), query.findByToken(workspace.token)!!.sources.map { it.sourceType })
        val draft = success(service.createDraft(workspace.id, DraftOffer(option.id, run.run.id, "Postcard batch", "Five postcards",
            proposal.suggestedVariants, option.unitCost, "Ships in two weeks", option.minBatch, option.maxCapacity,
            Duration.ofMinutes(15), supportingWishIds = proposal.rationale.map { it.wishId }.distinct())))
        assertEquals(listOf("ANALYSIS"), query.findByToken(workspace.token)!!.sources.map { it.sourceType })
        val ref = CampaignRef(workspace.id, draft.campaignId)
        success(service.publish(ref, Duration.ofMinutes(15)))
        assertEquals(setOf("ANALYSIS", "OFFER"), query.findByToken(workspace.token)!!.sources.map { it.sourceType }.toSet())
        return Fixture(workspace, ref)
    }

    private fun authorize(fixture: Fixture, quantity: Int): Pledge {
        clock.current = clock.current.plusSeconds(1)
        val order = OrderId("order-${UUID.randomUUID()}")
        gateway.fake.createOrderResults.add(received(OrderDetails(order), PaymentStatus.CREATED))
        val started = success(service.startPledge(fixture.ref, quantity))
        gateway.fake.authorizeOrderResults.add(received(AuthorizationDetails(AuthorizationId("authorization-${started.id}"),
            Money(started.amount, started.currency), clock.instant().plusSeconds(86_400), orderId = order,
            payerEmail = "buyer@example.test", shippingAddress = ShippingAddress(fullName = "Synthetic Buyer",
                addressLine1 = "Synthetic Street")), PaymentStatus.CREATED))
        success(service.completePledge(PledgeRef(fixture.ref, started.id)))
        return pledges.findById(started.id).orElseThrow()
    }

    private fun capture(pledge: Pledge) = CaptureDetails(CaptureId("capture-${pledge.id}"),
        Money(pledge.amount, pledge.currency), AuthorizationId(pledge.authorizationId!!))
    private fun document(fixture: Fixture) = assertNotNull(query.findByToken(fixture.workspace.token))
    private fun <T> received(resource: T, status: PaymentStatus): PaymentResult<T> = PaymentResult.Received(resource, status)
    private fun <T> success(result: ServiceResult<T>): T = assertIs<ServiceResult.Success<T>>(result).value
    private data class Fixture(val workspace: Workspace, val ref: CampaignRef)
}

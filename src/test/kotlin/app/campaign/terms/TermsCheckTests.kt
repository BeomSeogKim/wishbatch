package app.campaign.terms

import app.campaign.model.*
import app.campaign.port.*
import app.campaign.provenance.DecisionRecordQuery
import app.campaign.provenance.DecisionRecordService
import app.campaign.rules.PaymentAction
import app.campaign.rules.idempotencyKey
import app.campaign.service.*
import app.campaign.store.*
import app.testing.PaymentCall
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(CampaignServiceTests.PaymentTestConfiguration::class)
class TermsCheckTests @Autowired constructor(
    private val checks: TermsCheckService,
    private val seals: TermsSealQuery,
    private val records: DecisionRecordService,
    private val query: DecisionRecordQuery,
    private val service: CampaignService,
    private val persistence: CampaignPersistence,
    private val workspaces: WorkspaceRepository,
    private val offers: OfferRepository,
    private val campaigns: CampaignRepository,
    private val pledges: PledgeRepository,
    private val events: EventRepository,
    private val entities: EntityManager,
    private val gateway: CrashableGateway,
    private val clock: MutableClock,
    private val mapper: JsonMapper,
) {
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val owned = mutableListOf<UUID>()

    @BeforeEach
    fun reset() {
        gateway.reset()
        clock.current = start
    }

    @AfterEach
    fun cleanup() {
        owned.forEach { id -> persistence.checkpoint {
            val ids = "select c.id from Campaign c where c.offerId in (select o.id from Offer o where o.workspaceId = :id)"
            listOf(
                "DecisionRecordEntry e where e.pledgeId in (select r.pledgeId from PledgeDecisionRecord r where r.workspaceId = :id)",
                "PledgeDecisionRecord r where r.workspaceId = :id", "DecisionSourceSnapshot s where s.workspaceId = :id",
                "Event e where e.campaignId in ($ids)", "Pledge p where p.campaignId in ($ids)",
                "Campaign c where c.id in ($ids)", "Offer o where o.workspaceId = :id", "Workspace w where w.id = :id",
            ).forEach { entities.createQuery("delete from $it").setParameter("id", id).executeUpdate() }
        } }
    }

    @ParameterizedTest
    @ValueSource(strings = ["long-title", "blank-title", "missing-seal", "changed-title", "changed-price", "missing-time"])
    fun `incompatible published metadata rejects new pledge without creating an order`(kind: String) {
        val title = when (kind) {
            "long-title" -> "x".repeat(126) + "\uD83D\uDE00\uD83D\uDE00"
            "blank-title" -> " "
            else -> "Print"
        }
        val f = fixture(sealed = kind != "missing-seal", title = title)
        val originalSeal = records.sealedOffer(f.workspace.id, f.offer.id)
        persistence.checkpoint {
            when (kind) {
                "changed-title" -> entities.createNativeQuery("update offers set title = 'Changed' where id = :id")
                    .setParameter("id", f.offer.id).executeUpdate()
                "changed-price" -> entities.createNativeQuery("update offers set unit_price = 201 where id = :id")
                    .setParameter("id", f.offer.id).executeUpdate()
                "missing-time" -> campaigns.saveAndFlush(f.campaign.apply { openedAt = null })
            }
        }
        val before = pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(f.campaign.id).map { it.id }
        val rejection = assertIs<ServiceResult.Rejected>(service.startPledge(f.ref, 1))
        assertEquals(when (kind) {
            "long-title", "blank-title" -> Rejection.INVALID_OFFER
            "changed-title", "changed-price" -> Rejection.PAYMENT_MISMATCH
            else -> Rejection.PAYMENT_UNAVAILABLE
        }, rejection.reason)
        assertNull(rejection.pledge)
        assertEquals(before, pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(f.campaign.id).map { it.id })
        assertEquals(CampaignState.OPEN, campaigns.findById(f.campaign.id).orElseThrow().state)
        if (!kind.startsWith("changed")) assertEquals(originalSeal, records.sealedOffer(f.workspace.id, f.offer.id))
        assertTrue(gateway.fake.calls.isEmpty())
        assertTrue(gateway.orderReads.isEmpty())
    }

    @Test
    fun `two units use persisted quantity source hash and dated order evidence`() {
        val f = fixture()
        val seal = assertNotNull(records.sealedOffer(f.workspace.id, f.offer.id))
        val untrusted = Pledge(f.campaign.id, 1, 1, "EUR", createdAt = start, id = f.pledge.id)
        clock.current = start.plusSeconds(7)
        val check = checks.check(untrusted, TermsCheckStage.AUTHORIZATION)
        assertEquals(TermsCheckOutcome.MATCH, check.outcome)
        assertEquals(seal.termsHash, check.termsHash)
        assertEquals(2, check.quantity)
        assertEquals(400L, check.expectedAmountMinor)
        assertEquals(400L, check.providerAmountMinor)
        assertEquals("USD", check.providerCurrency)
        assertEquals(clock.instant(), check.checkedAt)
        assertEquals(idempotencyKey(f.pledge.id, PaymentAction.READ_ORDER), gateway.orderReads.single().idempotencyKey)
        val timeline = events.findByCampaignIdOrderByCreatedAtAscIdAsc(f.campaign.id)
        assertEquals(setOf("READ_ORDER_REQUEST", "READ_ORDER_RESPONSE", "TERMS_CHECK"), timeline.map { it.type }.toSet())
        assertEquals(check, mapper.readValue(timeline.single { it.type == "TERMS_CHECK" }.detail, TermsCheckObservation::class.java))
        assertEquals(PledgeState.CREATED, pledges.findById(f.pledge.id).orElseThrow().state)
    }

    @ParameterizedTest
    @ValueSource(strings = ["amount", "currency", "identity", "null-amount", "unavailable", "status"])
    fun `incorrect or unreadable provider evidence never matches`(kind: String) {
        val f = fixture()
        val order = OrderDetails(OrderId(f.pledge.paypalOrderId!!), amount = Money(400, "USD"))
        gateway.readOrderResults.add(when (kind) {
            "amount" -> received(order.copy(amount = Money(399, "USD")), PaymentStatus.APPROVED)
            "currency" -> received(order.copy(amount = Money(400, "EUR")), PaymentStatus.APPROVED)
            "identity" -> received(order.copy(orderId = OrderId("another-order")), PaymentStatus.APPROVED)
            "null-amount" -> received(order.copy(amount = null), PaymentStatus.APPROVED)
            "unavailable" -> PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE, debugId = "synthetic-debug")
            else -> received(order, PaymentStatus.CREATED)
        })
        val check = checks.check(f.pledge, TermsCheckStage.PRE_CAPTURE)
        assertEquals(if (kind in setOf("null-amount", "unavailable")) TermsCheckOutcome.UNAVAILABLE
            else TermsCheckOutcome.MISMATCH, check.outcome)
        assertTrue(gateway.fake.calls.isEmpty())
        val response = events.findByCampaignIdOrderByCreatedAtAscIdAsc(f.campaign.id).single { it.type == "READ_ORDER_RESPONSE" }
        if (kind == "unavailable") assertEquals("synthetic-debug", response.debugId)
    }

    @ParameterizedTest
    @ValueSource(strings = ["amount", "currency", "quantity", "overflow", "missing-seal", "missing-time", "missing-order"])
    fun `pledge and publication evidence must match sealed integer terms`(kind: String) {
        val f = fixture(sealed = kind != "missing-seal", price = if (kind == "overflow") Long.MAX_VALUE else 200)
        persistence.checkpoint {
            when (kind) {
                "amount" -> entities.createNativeQuery("update pledges set amount = 399 where id = :id")
                    .setParameter("id", f.pledge.id).executeUpdate()
                "currency" -> entities.createNativeQuery("update pledges set currency = 'EUR' where id = :id")
                    .setParameter("id", f.pledge.id).executeUpdate()
                "quantity" -> entities.createNativeQuery("update pledges set qty = 0 where id = :id")
                    .setParameter("id", f.pledge.id).executeUpdate()
                "missing-time" -> campaigns.saveAndFlush(f.campaign.apply { openedAt = null })
                "missing-order" -> pledges.saveAndFlush(f.pledge.apply { paypalOrderId = null })
                else -> Unit
            }
        }
        val check = checks.check(f.pledge, TermsCheckStage.AUTHORIZATION)
        assertEquals(if (kind.startsWith("missing")) TermsCheckOutcome.UNAVAILABLE else TermsCheckOutcome.MISMATCH, check.outcome)
        assertTrue(gateway.orderReads.isEmpty())
        assertTrue(gateway.fake.calls.isEmpty())
        if (kind == "overflow") {
            assertEquals("AMOUNT_OVERFLOW", check.reason)
            assertNull(check.expectedAmountMinor)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["offer", "hash", "payload", "workspace"])
    fun `changed source identity payload or hash has no usable seal`(kind: String) {
        val f = fixture()
        val original = assertNotNull(records.sealedOffer(f.workspace.id, f.offer.id))
        persistence.checkpoint {
            val sql = when (kind) {
                "offer" -> "update offers set unit_price = 201 where id = :id"
                "hash" -> "update decision_source_snapshots set hash = 'changed' where source_id = :id"
                "payload" -> "update decision_source_snapshots set payload = '{}' where source_id = :id"
                else -> "update decision_source_snapshots set workspace_id = :workspace where source_id = :id"
            }
            val statement = entities.createNativeQuery(sql).setParameter("id", f.offer.id)
            if (kind == "workspace") statement.setParameter("workspace", UUID.randomUUID())
            statement.executeUpdate()
        }
        assertFailsWith<SealedOfferChanged> { records.sealedOffer(f.workspace.id, f.offer.id) }
        val view = assertNotNull(seals.findByOffer(f.workspace.id, f.offer.id))
        assertEquals("MISMATCH", view.availability)
        assertNull(view.termsHash)
        val check = checks.check(f.pledge, TermsCheckStage.PRE_CAPTURE)
        assertEquals(TermsCheckOutcome.MISMATCH, check.outcome)
        assertEquals("SEAL_CHANGED", check.reason)
        assertNull(check.termsHash)
        assertEquals(64, original.termsHash.length)
        assertNull(records.sealedOffer(UUID.randomUUID(), f.offer.id))
        assertTrue(gateway.orderReads.isEmpty())
    }

    @Test
    fun `matching authorization then tampered currency prevents capture and releases hold`() {
        val f = fixture()
        authorize(f)
        clock.current = start.plusSeconds(3)
        gateway.readOrderResults.add(received(OrderDetails(OrderId(f.pledge.paypalOrderId!!), amount = Money(400, "EUR")), PaymentStatus.COMPLETED))
        release(f.pledge)
        val result = success(service.confirmProduction(f.ref))
        assertEquals(CampaignState.NOT_FUNDED, result.state)
        assertEquals(PledgeState.VOIDED, result.pledges.single().state)
        assertEquals(0L, result.counts.paid)
        assertTrue(gateway.fake.calls.none { it is PaymentCall.Capture })
        val observations = observations(f)
        assertEquals(listOf(TermsCheckOutcome.MATCH, TermsCheckOutcome.MISMATCH), observations.map { it.outcome })
        assertEquals(listOf(TermsCheckStage.AUTHORIZATION, TermsCheckStage.PRE_CAPTURE), observations.map { it.stage })
        assertEquals(observations.first().termsHash, observations.last().termsHash)
        assertEquals(clock.instant(), observations.last().checkedAt)
        val record = assertNotNull(query.findByToken(f.workspace.token)).pledges.single()
        assertEquals(2, record.termsChecks.size)
        assertEquals("RECORDED", record.termsChecksAvailability)
        assertEquals("MISMATCH", record.termsChecks.last().observation["outcome"].asString())
        assertEquals("INTACT", record.integrity)
        assertTrue(record.entries.none { it.kind == "TERMS_CHECK" })
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `obtained hold is voided on mismatch while unknown release stays unresolved`(confirmed: Boolean) {
        val f = fixture()
        f.pledge.authorizationId = "known-hold"
        pledges.saveAndFlush(f.pledge)
        gateway.readOrderResults.add(received(OrderDetails(OrderId(f.pledge.paypalOrderId!!), amount = Money(400, "EUR")), PaymentStatus.COMPLETED))
        gateway.fake.readAuthorizationResults.add(received(authorization(f.pledge), PaymentStatus.CREATED))
        if (confirmed) release(f.pledge) else {
            gateway.fake.voidAuthorizationResults.add(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE))
            gateway.fake.readAuthorizationResults.add(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE))
        }
        val rejected = assertIs<ServiceResult.Rejected>(service.completePledge(PledgeRef(f.ref, f.pledge.id)))
        assertEquals(Rejection.PAYMENT_MISMATCH, rejected.reason)
        assertEquals(if (confirmed) PledgeState.VOIDED else PledgeState.VOID_FAILED, rejected.pledge!!.state)
        assertEquals(1, gateway.fake.calls.filterIsInstance<PaymentCall.VoidAuthorization>().size)
        assertTrue(gateway.fake.calls.none { it is PaymentCall.AuthorizeOrder || it is PaymentCall.Capture })
    }

    @Test
    fun `unavailable evidence refuses fresh writes without guessing release`() {
        val f = fixture()
        gateway.readOrderResults.add(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE))
        assertEquals(Rejection.PAYMENT_UNAVAILABLE,
            assertIs<ServiceResult.Rejected>(service.completePledge(PledgeRef(f.ref, f.pledge.id))).reason)
        assertTrue(gateway.fake.calls.isEmpty())
        f.pledge.authorizationId = "existing-hold"
        f.pledge.state = PledgeState.AUTHORIZED
        pledges.saveAndFlush(f.pledge)
        gateway.readOrderResults.add(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE))
        gateway.fake.voidAuthorizationResults.add(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE))
        gateway.fake.readAuthorizationResults.add(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE))
        val result = success(service.confirmProduction(f.ref))
        assertEquals(CampaignState.NOT_FUNDED, result.state)
        assertEquals(PledgeState.VOID_FAILED, result.pledges.single().state)
        assertTrue(result.needsAttention.isNotEmpty())
        assertTrue(gateway.fake.calls.none { it is PaymentCall.Capture })
    }

    @ParameterizedTest
    @ValueSource(strings = ["mismatch-voided", "mismatch-unavailable", "unavailable-voided", "unavailable-unavailable"])
    fun `known hold terms rejection still attempts verified cleanup after failed authorization read`(scenario: String) {
        val f = fixture()
        f.pledge.authorizationId = "known-hold"
        pledges.saveAndFlush(f.pledge)
        val mismatched = scenario.startsWith("mismatch")
        gateway.readOrderResults.add(if (mismatched)
            received(OrderDetails(OrderId(f.pledge.paypalOrderId!!), amount = Money(400, "EUR")), PaymentStatus.COMPLETED)
        else PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE))
        gateway.fake.readAuthorizationResults.add(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE))
        val released = scenario.endsWith("voided")
        if (released) release(f.pledge) else {
            gateway.fake.voidAuthorizationResults.add(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE))
            gateway.fake.readAuthorizationResults.add(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE))
        }
        val rejected = assertIs<ServiceResult.Rejected>(service.completePledge(PledgeRef(f.ref, f.pledge.id)))
        assertEquals(if (mismatched) Rejection.PAYMENT_MISMATCH else Rejection.PAYMENT_UNAVAILABLE, rejected.reason)
        val saved = pledges.findById(f.pledge.id).orElseThrow()
        assertEquals(if (released) PledgeState.VOIDED else PledgeState.VOID_FAILED, saved.state)
        assertEquals("known-hold", saved.authorizationId)
        if (!released) assertEquals("VOID_UNCONFIRMED", saved.lastError)
        assertEquals(!released, campaigns.findById(f.campaign.id).orElseThrow().needsAttention)
        val request = gateway.fake.calls.filterIsInstance<PaymentCall.VoidAuthorization>().single()
        assertEquals(AuthorizationId("known-hold"), request.authorizationId)
        assertEquals(idempotencyKey(saved.id, PaymentAction.VOID), request.idempotencyKey)
        assertTrue(gateway.fake.calls.none { it is PaymentCall.AuthorizeOrder || it is PaymentCall.Capture })
        assertEquals(if (released) 1 else 2, gateway.fake.calls.filterIsInstance<PaymentCall.ReadAuthorization>().size)
        assertEquals(if (mismatched) TermsCheckOutcome.MISMATCH else TermsCheckOutcome.UNAVAILABLE, observations(f).single().outcome)
    }

    @Test
    fun `authorization timeout recovers and voids a hold after later terms mismatch`() {
        val f = fixture()
        gateway.fake.authorizeOrderResults.add(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE))
        assertIs<ServiceResult.Rejected>(service.completePledge(PledgeRef(f.ref, f.pledge.id)))
        gateway.readOrderResults.add(received(OrderDetails(OrderId(f.pledge.paypalOrderId!!), amount = Money(399, "USD")), PaymentStatus.COMPLETED))
        f.pledge.authorizationId = "recovered-hold"
        gateway.fake.authorizeOrderResults.add(received(authorization(f.pledge), PaymentStatus.CREATED))
        release(f.pledge)
        assertEquals(PledgeState.VOIDED,
            assertIs<ServiceResult.Rejected>(service.completePledge(PledgeRef(f.ref, f.pledge.id))).pledge!!.state)
        val attempts = gateway.fake.calls.filterIsInstance<PaymentCall.AuthorizeOrder>()
        assertEquals(2, attempts.size)
        assertEquals(setOf(idempotencyKey(f.pledge.id, PaymentAction.AUTHORIZE)), attempts.map { it.idempotencyKey }.toSet())
    }

    @Test
    fun `capture applied before interruption recovers original key despite later source drift`() {
        val f = fixture()
        authorize(f)
        val capture = CaptureDetails(CaptureId("completed-capture"), Money(400, "USD"), AuthorizationId(f.pledge.authorizationId!!))
        gateway.fake.captureResults.add(received(capture, PaymentStatus.COMPLETED))
        gateway.crashAfterCaptureNumber = 1
        assertFailsWith<SimulatedProcessCrash> { service.confirmProduction(f.ref) }
        persistence.checkpoint {
            entities.createNativeQuery("update offers set delivery_terms = 'Changed delivery' where id = :id")
                .setParameter("id", f.offer.id).executeUpdate()
        }
        gateway.fake.readAuthorizationResults.add(received(authorization(f.pledge), PaymentStatus.CAPTURED))
        gateway.fake.captureResults.add(received(capture, PaymentStatus.COMPLETED))
        assertEquals(CampaignState.FUNDED, success(service.confirmProduction(f.ref)).state)
        val attempts = gateway.fake.calls.filterIsInstance<PaymentCall.Capture>()
        assertEquals(2, attempts.size)
        assertEquals(setOf(idempotencyKey(f.pledge.id, PaymentAction.CAPTURE)), attempts.map { it.idempotencyKey }.toSet())
        assertEquals(1, observations(f).count { it.stage == TermsCheckStage.PRE_CAPTURE })
        assertEquals("CHANGED", assertNotNull(query.findByToken(f.workspace.token)).pledges.single().integrity)
        success(service.confirmProduction(f.ref))
        assertEquals(2, gateway.fake.calls.filterIsInstance<PaymentCall.Capture>().size)
    }

    private fun fixture(sealed: Boolean = true, price: Long = 200, title: String = "Print"): Fixture {
        val workspace = workspaces.saveAndFlush(Workspace("terms-${UUID.randomUUID()}", start)).also { owned.add(it.id) }
        val offer = offers.saveAndFlush(Offer(workspace.id, UUID.randomUUID(), UUID.randomUUID(), title, "A5 print",
            emptyMap(), price, "USD", "Shipping included", "US", 1, 5, 3))
        val campaign = campaigns.saveAndFlush(Campaign(offer.id, CampaignState.OPEN, openedAt = start,
            closesAt = start.plusSeconds(900), confirmDeadline = start.plusSeconds(44100)))
        if (sealed) records.recordOffer(offer)
        val pledge = pledges.saveAndFlush(Pledge(campaign.id, 2, 400, "USD", paypalOrderId = "order-${UUID.randomUUID()}", createdAt = start))
        gateway.registerOrder(pledge)
        return Fixture(workspace, offer, campaign, pledge)
    }

    private fun authorize(f: Fixture) {
        f.pledge.authorizationId = "hold-${f.pledge.id}"
        gateway.fake.authorizeOrderResults.add(received(authorization(f.pledge), PaymentStatus.CREATED))
        assertEquals(PledgeState.AUTHORIZED, success(service.completePledge(PledgeRef(f.ref, f.pledge.id))).state)
    }
    private fun release(pledge: Pledge) {
        gateway.fake.voidAuthorizationResults.add(received(VoidDetails(AuthorizationId(pledge.authorizationId!!)), PaymentStatus.VOIDED))
    }
    private fun authorization(p: Pledge) = AuthorizationDetails(AuthorizationId(p.authorizationId!!),
        Money(400, "USD"), start.plusSeconds(86400), OrderId(p.paypalOrderId!!))
    private fun observations(f: Fixture) = events.findByCampaignIdOrderByCreatedAtAscIdAsc(f.campaign.id)
        .filter { it.type == "TERMS_CHECK" }.map { mapper.readValue(it.detail, TermsCheckObservation::class.java) }
    private fun <T> received(resource: T, status: PaymentStatus) = PaymentResult.Received(resource, status)
    private fun <T> success(result: ServiceResult<T>): T = assertIs<ServiceResult.Success<T>>(result).value
    private data class Fixture(val workspace: Workspace, val offer: Offer, val campaign: Campaign, val pledge: Pledge) {
        val ref get() = CampaignRef(workspace.id, campaign.id)
    }
}

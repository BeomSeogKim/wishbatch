package app.campaign

import app.campaign.model.Money
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishSource
import app.campaign.port.AnalysisFailure
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalysisResult
import app.campaign.port.AnalysisValidationResult
import app.campaign.port.AuthorizationDetails
import app.campaign.port.AuthorizationId
import app.campaign.port.CaptureDetails
import app.campaign.port.CaptureId
import app.campaign.port.CreateAuthorizeOrder
import app.campaign.port.OrderDetails
import app.campaign.port.OrderId
import app.campaign.port.PaymentResult
import app.campaign.port.PaymentStatus
import app.campaign.port.RefundDetails
import app.campaign.port.RefundId
import app.campaign.port.VoidDetails
import app.testing.InMemoryPaymentGateway
import app.testing.InMemoryWishAnalyzer
import app.testing.PaymentCall
import app.testing.fixedClock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class TestFakesTests {
    private val amount = Money(2500, "USD")
    private val orderId = OrderId("order-1")
    private val authorizationId = AuthorizationId("authorization-1")
    private val captureId = CaptureId("capture-1")
    private val refundId = RefundId("refund-1")
    private val instant = Instant.parse("2026-01-01T12:00:00Z")

    @Test
    fun `every payment operation returns its script and records typed arguments and key`() {
        val gateway = InMemoryPaymentGateway()
        val request = CreateAuthorizeOrder(UUID.randomUUID(), amount)
        val order = PaymentResult.Received(OrderDetails(orderId), PaymentStatus.CREATED)
        val authorization = PaymentResult.Received(
            AuthorizationDetails(authorizationId, amount, instant, orderId), PaymentStatus.CREATED,
        )
        val capture = PaymentResult.Received(CaptureDetails(captureId, amount, authorizationId), PaymentStatus.COMPLETED)
        val void = PaymentResult.Received(VoidDetails(authorizationId), PaymentStatus.VOIDED)
        val refund = PaymentResult.Received(RefundDetails(refundId, amount, captureId), PaymentStatus.COMPLETED)
        gateway.createOrderResults.addLast(order)
        gateway.authorizeOrderResults.addLast(authorization)
        gateway.readAuthorizationResults.addLast(authorization)
        gateway.captureResults.addLast(capture)
        gateway.voidAuthorizationResults.addLast(void)
        gateway.refundCaptureResults.addLast(refund)
        gateway.readCaptureResults.addLast(capture)
        gateway.readRefundResults.addLast(refund)

        assertSame(order, gateway.createAuthorizeOrder(request, "create-key"))
        assertSame(authorization, gateway.authorizeOrder(orderId, "authorize-key"))
        assertSame(authorization, gateway.readAuthorization(authorizationId, "authorization-read-key"))
        assertSame(capture, gateway.capture(authorizationId, "capture-key"))
        assertSame(void, gateway.voidAuthorization(authorizationId, "void-key"))
        assertSame(refund, gateway.refundCapture(captureId, "refund-key"))
        assertSame(capture, gateway.readCapture(captureId, "capture-read-key"))
        assertSame(refund, gateway.readRefund(refundId, "refund-read-key"))
        assertEquals(
            listOf(
                PaymentCall.CreateOrder(request, "create-key"),
                PaymentCall.AuthorizeOrder(orderId, "authorize-key"),
                PaymentCall.ReadAuthorization(authorizationId, "authorization-read-key"),
                PaymentCall.Capture(authorizationId, "capture-key"),
                PaymentCall.VoidAuthorization(authorizationId, "void-key"),
                PaymentCall.RefundCapture(captureId, "refund-key"),
                PaymentCall.ReadCapture(captureId, "capture-read-key"),
                PaymentCall.ReadRefund(refundId, "refund-read-key"),
            ),
            gateway.calls,
        )
    }

    @Test
    fun `capture scripts preserve pending declined and transport results in FIFO order on repeated keys`() {
        val gateway = InMemoryPaymentGateway()
        val pending = PaymentResult.Received(CaptureDetails(captureId, amount), PaymentStatus.PENDING, "debug-pending")
        val declined = PaymentResult.Failed(PaymentStatus.DECLINED, "debug-declined", "INSTRUMENT_DECLINED")
        val transport = PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE, message = "Timeout")
        gateway.captureResults.addAll(listOf(pending, declined, transport))

        assertSame(pending, gateway.capture(authorizationId, "repeated-key"))
        val firstCallSnapshot = gateway.calls
        assertSame(declined, gateway.capture(authorizationId, "repeated-key"))
        assertSame(transport, gateway.capture(authorizationId, "repeated-key"))

        assertEquals(List(3) { PaymentCall.Capture(authorizationId, "repeated-key") }, gateway.calls)
        assertEquals(1, firstCallSnapshot.size)
        assertTrue(gateway.captureResults.isEmpty())
    }

    @Test
    fun `capture retry preserves recovered capture ID and independent resource statuses`() {
        val gateway = InMemoryPaymentGateway()
        gateway.captureResults.addAll(
            listOf(
                PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE, message = "Timeout"),
                PaymentResult.Failed(PaymentStatus.ALREADY_CAPTURED),
            ),
        )
        gateway.readAuthorizationResults.addLast(
            PaymentResult.Received(
                AuthorizationDetails(authorizationId, amount, instant, captureId = captureId),
                PaymentStatus.CAPTURED,
            ),
        )
        gateway.readCaptureResults.addAll(
            listOf(
                PaymentResult.Received(CaptureDetails(captureId, amount, authorizationId), PaymentStatus.PENDING),
                PaymentResult.Received(CaptureDetails(captureId, amount, authorizationId), PaymentStatus.COMPLETED),
            ),
        )
        val refund = PaymentResult.Received(RefundDetails(refundId, amount, captureId), PaymentStatus.COMPLETED)
        gateway.refundCaptureResults.addLast(refund)

        assertEquals(PaymentStatus.TRANSPORT_FAILURE, gateway.capture(authorizationId, "capture-key").status)
        assertEquals(PaymentStatus.ALREADY_CAPTURED, gateway.capture(authorizationId, "capture-key").status)
        val authorization = assertIs<PaymentResult.Received<AuthorizationDetails>>(
            gateway.readAuthorization(authorizationId, "authorization-read-key"),
        )
        assertEquals(PaymentStatus.CAPTURED, authorization.status)
        val recoveredCaptureId = assertNotNull(authorization.resource.captureId)
        assertEquals(captureId, recoveredCaptureId)
        assertEquals(PaymentStatus.PENDING, gateway.readCapture(recoveredCaptureId, "capture-read-key").status)
        assertEquals(PaymentStatus.COMPLETED, gateway.readCapture(recoveredCaptureId, "capture-read-key").status)
        assertSame(refund, gateway.refundCapture(recoveredCaptureId, "refund-key"))
        assertEquals(
            listOf(
                PaymentCall.Capture(authorizationId, "capture-key"),
                PaymentCall.Capture(authorizationId, "capture-key"),
                PaymentCall.ReadAuthorization(authorizationId, "authorization-read-key"),
                PaymentCall.ReadCapture(captureId, "capture-read-key"),
                PaymentCall.ReadCapture(captureId, "capture-read-key"),
                PaymentCall.RefundCapture(captureId, "refund-key"),
            ),
            gateway.calls,
        )
    }

    @Test
    fun `failure after N counts all operations records attempts and leaves queued results untouched`() {
        val failure = PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE, "debug-outage", "UNAVAILABLE")
        val gateway = InMemoryPaymentGateway(failAfterCalls = 2, failure = failure)
        val pending = PaymentResult.Received(CaptureDetails(captureId, amount), PaymentStatus.PENDING)
        gateway.captureResults.addLast(pending)
        gateway.readCaptureResults.addAll(listOf(pending, pending))

        assertSame(pending, gateway.capture(authorizationId, "capture-key"))
        assertSame(pending, gateway.readCapture(captureId, "read-key"))
        assertSame(failure, gateway.readCapture(captureId, "read-key"))
        assertSame(failure, gateway.refundCapture(captureId, "refund-key"))
        assertEquals(1, gateway.readCaptureResults.size)
        assertEquals(
            listOf(
                PaymentCall.Capture(authorizationId, "capture-key"),
                PaymentCall.ReadCapture(captureId, "read-key"),
                PaymentCall.ReadCapture(captureId, "read-key"),
                PaymentCall.RefundCapture(captureId, "refund-key"),
            ),
            gateway.calls,
        )
    }

    @Test
    fun `zero call threshold fails immediately and negative threshold is invalid`() {
        val gateway = InMemoryPaymentGateway(failAfterCalls = 0)
        assertEquals(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE), gateway.capture(authorizationId, "key"))
        assertEquals(listOf(PaymentCall.Capture(authorizationId, "key")), gateway.calls)
        assertFailsWith<IllegalArgumentException> { InMemoryPaymentGateway(failAfterCalls = -1) }
    }

    @Test
    fun `unscripted payment operations fail clearly and still record attempts`() {
        val gateway = InMemoryPaymentGateway()
        val operations = listOf<() -> Unit>(
            { gateway.createAuthorizeOrder(CreateAuthorizeOrder(UUID.randomUUID(), amount), "key") },
            { gateway.authorizeOrder(orderId, "key") },
            { gateway.readAuthorization(authorizationId, "key") },
            { gateway.capture(authorizationId, "key") },
            { gateway.voidAuthorization(authorizationId, "key") },
            { gateway.refundCapture(captureId, "key") },
            { gateway.readCapture(captureId, "key") },
            { gateway.readRefund(refundId, "key") },
        )
        operations.forEach { operation ->
            val error = assertFailsWith<AssertionError> { operation() }
            assertEquals("No scripted payment result for ${gateway.calls.last().javaClass.simpleName}", error.message)
        }
        assertEquals(8, gateway.calls.size)
    }

    @Test
    fun `analyzer returns queued success and failure and preserves input snapshots`() {
        val analyzer = InMemoryWishAnalyzer()
        val workspaceId = UUID.randomUUID()
        val attributes = mutableMapOf("material" to "cotton")
        val colors = mutableListOf("blue")
        val variants = mutableMapOf<String, List<String>>("color" to colors)
        val option = Option(workspaceId, "Shirt", "shirt", attributes, variants, 1000, "USD", 10, 50, "Two weeks", "US")
        val wish = Wish(workspaceId, "A blue shirt", WishSource.ONSITE, "Fan", instant)
        val options = mutableListOf(option)
        val wishes = mutableListOf(wish)
        val output = AnalysisOutput(emptyList(), emptyList(), null, "No compatible requests", emptyList(), emptyList(), emptyList())
        val success = AnalysisResult.Success("test-model", AnalysisValidationResult.Accepted(output, emptyList()))
        val failure = AnalysisResult.Failure(AnalysisFailure.PROVIDER_FAILURE)
        analyzer.results.addAll(listOf(success, failure))

        assertSame(success, analyzer.analyze(options, wishes))
        val firstCalls = analyzer.calls
        options.clear()
        wishes.clear()
        attributes["material"] = "wool"
        colors.add("red")
        variants.clear()
        wish.hidden = true
        assertSame(failure, analyzer.analyze(options, wishes))

        val snapshot = firstCalls.single()
        assertEquals(option.id, snapshot.options.single().id)
        assertEquals(mapOf("material" to "cotton"), snapshot.options.single().fixedAttributes)
        assertEquals(mapOf("color" to listOf("blue")), snapshot.options.single().allowedVariants)
        assertEquals(wish.id, snapshot.wishes.single().id)
        assertEquals(workspaceId, snapshot.wishes.single().workspaceId)
        assertEquals("A blue shirt", snapshot.wishes.single().text)
        assertEquals(WishSource.ONSITE, snapshot.wishes.single().source)
        assertEquals("Fan", snapshot.wishes.single().displayName)
        assertEquals(instant, snapshot.wishes.single().createdAt)
        assertFalse(snapshot.wishes.single().hidden)
        assertEquals(2, analyzer.calls.size)
        assertTrue(analyzer.calls.last().options.isEmpty())
        assertTrue(analyzer.calls.last().wishes.isEmpty())
        assertTrue(analyzer.results.isEmpty())
    }

    @Test
    fun `unscripted analyzer fails clearly after recording inputs`() {
        val analyzer = InMemoryWishAnalyzer()
        val error = assertFailsWith<AssertionError> { analyzer.analyze(emptyList(), emptyList()) }
        assertEquals("No scripted wish analysis result", error.message)
        assertEquals(1, analyzer.calls.size)
    }

    @Test
    fun `fixed clock holds the instant with UTC default or an explicit zone`() {
        val utc = fixedClock(instant)
        assertEquals(ZoneOffset.UTC, utc.zone)
        repeat(3) { assertEquals(instant, Instant.now(utc)) }
        val zone = ZoneId.of("Asia/Seoul")
        val zoned = fixedClock(instant, zone)
        assertEquals(zone, zoned.zone)
        assertEquals(instant, zoned.instant())
        assertEquals(instant, utc.withZone(zone).instant())
    }
}

package app.paypal

import app.campaign.model.Money
import app.campaign.model.ShippingAddress
import app.campaign.port.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.env.MockEnvironment
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.*
import org.springframework.test.web.client.response.MockRestResponseCreators.*
import org.springframework.web.client.RestClient
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@ExtendWith(OutputCaptureExtension::class)
class PayPalClientTests {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val clock = MutableClock()
    private val client = PayPalClient(PayPalProperties(PayPalProperties.SANDBOX_URL, "synthetic-client", "synthetic-secret"), builder, clock)
    private val amount = "\"amount\":{\"value\":\"12.34\",\"currency_code\":\"USD\"}"

    @Test
    fun `create requests an authorize order with exact server money and shipping`() {
        token()
        val pledgeId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        server.expect(requestTo(url("/v2/checkout/orders"))).andExpect(method(HttpMethod.POST))
            .andExpect(header("PayPal-Request-Id", "create-key"))
            .andExpect(header("Prefer", "return=representation"))
            .andExpect(header("Authorization", "Bearer synthetic-token"))
            .andExpect(content().json("""{
                "intent":"AUTHORIZE",
                "purchase_units":[{"custom_id":"$pledgeId","amount":{"currency_code":"USD","value":"12.34"}}],
                "payment_source":{"paypal":{"experience_context":{"shipping_preference":"GET_FROM_FILE"}}}
            }"""))
            .andRespond(withSuccess("""{"id":"ORDER1","status":"CREATED"}""", MediaType.APPLICATION_JSON)
                .header("PayPal-Debug-Id", "create-debug"))
        val result = client.createAuthorizeOrder(CreateAuthorizeOrder(pledgeId, Money(1234, "USD")), "create-key")
        assertEquals(PaymentResult.Received(OrderDetails(OrderId("ORDER1")), PaymentStatus.CREATED, "create-debug"), result)
        server.verify()
    }

    @Test
    fun `create maps documented order states without claiming buyer approval`() {
        token()
        val statuses = mapOf(
            "CREATED" to PaymentStatus.CREATED,
            "PAYER_ACTION_REQUIRED" to PaymentStatus.CREATED,
            "SAVED" to PaymentStatus.PENDING,
            "APPROVED" to PaymentStatus.APPROVED,
            "VOIDED" to PaymentStatus.VOIDED,
            "COMPLETED" to PaymentStatus.COMPLETED,
        )
        for (status in statuses.keys) {
            expect(HttpMethod.POST, "/v2/checkout/orders", "create-key", """{"id":"ORDER1","status":"$status"}""")
        }
        for ((providerStatus, status) in statuses) {
            assertEquals(PaymentResult.Received(OrderDetails(OrderId("ORDER1")), status, "response-debug"),
                client.createAuthorizeOrder(CreateAuthorizeOrder(UUID.randomUUID(), Money(1234, "USD")), "create-key"), providerStatus)
        }
        server.verify()
    }

    @Test
    fun `authorization responses preserve documented statuses on authorize read and void`() {
        token()
        val statuses = mapOf(
            "CREATED" to PaymentStatus.CREATED,
            "CAPTURED" to PaymentStatus.CAPTURED,
            "DENIED" to PaymentStatus.DECLINED,
            "PARTIALLY_CAPTURED" to PaymentStatus.CAPTURED,
            "VOIDED" to PaymentStatus.VOIDED,
            "PENDING" to PaymentStatus.PENDING,
        )
        for (status in statuses.keys) {
            val authorization = """{"id":"AUTH1","status":"$status",$amount}"""
            expect(HttpMethod.POST, "/v2/checkout/orders/ORDER1/authorize", "authorize-key", """{
                "id":"ORDER1","status":"COMPLETED",
                "purchase_units":[{"payments":{"authorizations":[$authorization]}}]
            }""")
            expect(HttpMethod.GET, "/v2/payments/authorizations/AUTH1", null, authorization)
            expect(HttpMethod.POST, "/v2/payments/authorizations/AUTH1/void", "void-key", authorization)
        }
        for ((providerStatus, status) in statuses) {
            val authorized = received(client.authorizeOrder(OrderId("ORDER1"), "authorize-key"))
            assertEquals(status, authorized.status, providerStatus)
            assertEquals(AuthorizationId("AUTH1"), authorized.resource.authorizationId)
            val read = received(client.readAuthorization(AuthorizationId("AUTH1"), "read-key"))
            assertEquals(status, read.status, providerStatus)
            assertEquals(AuthorizationId("AUTH1"), read.resource.authorizationId)
            assertEquals(PaymentResult.Received(VoidDetails(AuthorizationId("AUTH1")), status, "response-debug"),
                client.voidAuthorization(AuthorizationId("AUTH1"), "void-key"), providerStatus)
        }
        server.verify()
    }

    @Test
    fun `authorize uses nested status and returns payer shipping and expiry`(output: CapturedOutput) {
        token()
        expect(HttpMethod.POST, "/v2/checkout/orders/ORDER1/authorize", "authorize-key", """{
            "id":"ORDER1", "status":"COMPLETED", "payer":{"email_address":"buyer@example.test"},
            "purchase_units":[{"shipping":{"name":{"full_name":"Synthetic Buyer"},"address":{
                "address_line_1":"1 Example Street","address_line_2":"Unit 2","admin_area_1":"CA",
                "admin_area_2":"San Jose","postal_code":"95131","country_code":"US"}},
                "payments":{"authorizations":[{"id":"AUTH1","status":"CREATED",$amount,
                "expiration_time":"2026-12-11T00:00:00Z"}]}}]
        }""")
        val result = received(client.authorizeOrder(OrderId("ORDER1"), "authorize-key"))
        assertEquals(PaymentStatus.CREATED, result.status)
        assertEquals("response-debug", result.debugId)
        assertEquals(AuthorizationId("AUTH1"), result.resource.authorizationId)
        assertEquals(OrderId("ORDER1"), result.resource.orderId)
        assertEquals(Money(1234, "USD"), result.resource.amount)
        assertEquals(Instant.parse("2026-12-11T00:00:00Z"), result.resource.expiresAt)
        assertEquals("buyer@example.test", result.resource.payerEmail)
        assertEquals(ShippingAddress("Synthetic Buyer", "1 Example Street", "Unit 2", "CA", "San Jose", "95131", "US"),
            result.resource.shippingAddress)
        assertNull(result.resource.captureId)
        assertTrue(output.out.contains("oauth-debug"))
        assertTrue(output.out.contains("response-debug"))
        for (privateValue in listOf("synthetic-token", "synthetic-secret", "buyer@example.test", "Synthetic Buyer", "1 Example Street")) {
            assertFalse(output.all.contains(privateValue))
        }
        server.verify()
    }

    @Test
    fun `read authorization preserves missing evidence and terminal resource statuses`() {
        token()
        for (status in listOf("CAPTURED", "VOIDED")) {
            expect(HttpMethod.GET, "/v2/payments/authorizations/AUTH1", null, """{"id":"AUTH1","status":"$status",$amount}""")
        }
        for (status in listOf(PaymentStatus.CAPTURED, PaymentStatus.VOIDED)) {
            val result = received(client.readAuthorization(AuthorizationId("AUTH1"), "read-key"))
            assertEquals(status, result.status)
            assertNull(result.resource.orderId)
            assertNull(result.resource.captureId)
            assertNull(result.resource.expiresAt)
            assertNull(result.resource.payerEmail)
            assertNull(result.resource.shippingAddress)
        }
        server.verify()
    }

    @Test
    fun `capture and read accept documented statuses without treating partial refunds as complete`() {
        token()
        val statuses = mapOf(
            "COMPLETED" to PaymentStatus.COMPLETED,
            "PENDING" to PaymentStatus.PENDING,
            "DECLINED" to PaymentStatus.DECLINED,
            "PARTIALLY_REFUNDED" to PaymentStatus.PENDING,
            "REFUNDED" to PaymentStatus.REFUNDED,
            "FAILED" to PaymentStatus.FAILED,
        )
        for (status in statuses.keys) {
            expect(HttpMethod.POST, "/v2/payments/authorizations/AUTH1/capture", "capture-key",
                """{"id":"CAP1","status":"$status",$amount}""", """{"final_capture":true}""")
            expect(HttpMethod.GET, "/v2/payments/captures/CAP1", null, """{"id":"CAP1","status":"$status",$amount}""")
        }
        for ((providerStatus, status) in statuses) {
            val result = received(client.capture(AuthorizationId("AUTH1"), "capture-key"))
            assertEquals(status, result.status, providerStatus)
            assertEquals(CaptureId("CAP1"), result.resource.captureId)
            assertEquals(AuthorizationId("AUTH1"), result.resource.authorizationId)
            assertEquals(Money(1234, "USD"), result.resource.amount)
            val read = received(client.readCapture(CaptureId("CAP1"), "read-key"))
            assertEquals(status, read.status, providerStatus)
            assertEquals(CaptureId("CAP1"), read.resource.captureId)
        }
        server.verify()
    }

    @Test
    fun `void accepts an empty 204 response`() {
        token()
        server.expect(requestTo(url("/v2/payments/authorizations/AUTH1/void")))
            .andExpect(method(HttpMethod.POST)).andExpect(header("PayPal-Request-Id", "void-key"))
            .andRespond(withNoContent().header("PayPal-Debug-Id", "void-debug"))
        assertEquals(PaymentResult.Received(VoidDetails(AuthorizationId("AUTH1")), PaymentStatus.VOIDED, "void-debug"),
            client.voidAuthorization(AuthorizationId("AUTH1"), "void-key"))
        server.verify()
    }

    @Test
    fun `void requires a status in a 200 representation`() {
        token()
        for (body in listOf("", "{}", "[]", "true")) {
            expect(HttpMethod.POST, "/v2/payments/authorizations/AUTH1/void", "void-key", body)
        }
        repeat(4) {
            assertEquals(PaymentResult.Failed(PaymentStatus.FAILED, "response-debug", "INVALID_RESPONSE"),
                client.voidAuthorization(AuthorizationId("AUTH1"), "void-key"))
        }
        server.verify()
    }

    @Test
    fun `full refund and read preserve every documented outcome`() {
        token()
        val statuses = mapOf(
            "PENDING" to PaymentStatus.PENDING,
            "COMPLETED" to PaymentStatus.COMPLETED,
            "FAILED" to PaymentStatus.FAILED,
            "CANCELLED" to PaymentStatus.FAILED,
        )
        for (status in statuses.keys) {
            expect(HttpMethod.POST, "/v2/payments/captures/CAP1/refund", "refund-key",
                """{"id":"REF1","status":"$status",$amount}""", "{}")
            expect(HttpMethod.GET, "/v2/payments/refunds/REF1", null, """{"id":"REF1","status":"$status",$amount}""")
        }
        for ((providerStatus, status) in statuses) {
            val result = received(client.refundCapture(CaptureId("CAP1"), "refund-key"))
            assertEquals(status, result.status, providerStatus)
            assertEquals(RefundDetails(RefundId("REF1"), Money(1234, "USD"), CaptureId("CAP1")), result.resource)
            val read = received(client.readRefund(RefundId("REF1"), "read-key"))
            assertEquals(status, read.status, providerStatus)
            assertEquals(RefundId("REF1"), read.resource.refundId)
        }
        server.verify()
    }

    @Test
    fun `capture and refund reads use resource status and explicit related ids`() {
        token()
        expect(HttpMethod.GET, "/v2/payments/captures/CAP1", null, """{"id":"CAP1","status":"REFUNDED",$amount,
            "supplementary_data":{"related_ids":{"authorization_id":"AUTH1"}}}""")
        expect(HttpMethod.GET, "/v2/payments/refunds/REF1", null, """{"id":"REF1","status":"COMPLETED",$amount,
            "supplementary_data":{"related_ids":{"capture_id":"CAP1"}}}""")
        assertEquals(PaymentResult.Received(CaptureDetails(CaptureId("CAP1"), Money(1234, "USD"), AuthorizationId("AUTH1")),
            PaymentStatus.REFUNDED, "response-debug"), client.readCapture(CaptureId("CAP1"), "read-key"))
        assertEquals(PaymentResult.Received(RefundDetails(RefundId("REF1"), Money(1234, "USD"), CaptureId("CAP1")),
            PaymentStatus.COMPLETED, "response-debug"), client.readRefund(RefundId("REF1"), "read-key"))
        server.verify()
    }

    @Test
    fun `known provider issues map without retrying any 4xx`() {
        token()
        val issues = mapOf(
            "AUTHORIZATION_ALREADY_CAPTURED" to PaymentStatus.ALREADY_CAPTURED,
            "AUTHORIZATION_VOIDED" to PaymentStatus.VOIDED,
            "AUTHORIZATION_EXPIRED" to PaymentStatus.EXPIRED,
            "INSTRUMENT_DECLINED" to PaymentStatus.DECLINED,
            "PAYMENT_DENIED" to PaymentStatus.DECLINED,
            "TRANSACTION_REFUSED" to PaymentStatus.DECLINED,
            "PAYER_CANNOT_PAY" to PaymentStatus.DECLINED,
            "CARD_DECLINED" to PaymentStatus.DECLINED,
            "UNKNOWN_ISSUE" to PaymentStatus.FAILED,
        )
        issues.keys.forEach { issue ->
            server.expect(requestTo(url("/v2/payments/authorizations/AUTH1/capture")))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"name":"UNPROCESSABLE_ENTITY","details":[{"issue":"$issue"}]}""")
                    .header("PayPal-Debug-Id", "error-debug"))
        }
        issues.forEach { (issue, status) ->
            assertEquals(PaymentResult.Failed(status, "error-debug", issue), client.capture(AuthorizationId("AUTH1"), "key"))
        }
        server.verify()
    }

    @Test
    fun `timeouts and server errors retry with the same key up to three times`(output: CapturedOutput) {
        token()
        repeat(3) { attempt ->
            val response = server.expect(requestTo(url("/v2/payments/authorizations/AUTH1/capture")))
                .andExpect(header("PayPal-Request-Id", "stable-key"))
            if (attempt == 1) response.andRespond(withException(SocketTimeoutException("synthetic timeout")))
            else response.andRespond(withServerError().header("PayPal-Debug-Id", "retry-$attempt"))
        }
        expect(HttpMethod.POST, "/v2/payments/authorizations/AUTH1/capture", "stable-key",
            """{"id":"CAP1","status":"COMPLETED",$amount}""")
        assertEquals(PaymentStatus.COMPLETED, client.capture(AuthorizationId("AUTH1"), "stable-key").status)
        assertTrue(output.out.contains("retry-0"))
        assertTrue(output.out.contains("retry-2"))
        server.verify()
    }

    @Test
    fun `exhausted retries preserve transport ambiguity and last debug id`() {
        token()
        repeat(4) {
            server.expect(requestTo(url("/v2/payments/authorizations/AUTH1/capture")))
                .andExpect(header("PayPal-Request-Id", "stable-key"))
                .andRespond(withServerError().header("PayPal-Debug-Id", "last-debug"))
        }
        assertEquals(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE, "last-debug", "HTTP_500"),
            client.capture(AuthorizationId("AUTH1"), "stable-key"))
        server.verify()
    }

    @Test
    fun `token is reused and refreshed thirty seconds before expiry`() {
        token(120)
        expect(HttpMethod.GET, "/v2/payments/captures/CAP1", null, """{"id":"CAP1","status":"COMPLETED",$amount}""")
        expect(HttpMethod.GET, "/v2/payments/captures/CAP1", null, """{"id":"CAP1","status":"COMPLETED",$amount}""")
        token(120)
        expect(HttpMethod.GET, "/v2/payments/captures/CAP1", null, """{"id":"CAP1","status":"COMPLETED",$amount}""")
        client.readCapture(CaptureId("CAP1"), "key")
        clock.now = clock.now.plusSeconds(89)
        client.readCapture(CaptureId("CAP1"), "key")
        clock.now = clock.now.plusSeconds(1)
        client.readCapture(CaptureId("CAP1"), "key")
        server.verify()
    }

    @Test
    fun `token errors are typed and a 401 is never retried`() {
        server.expect(requestTo(url("/v1/oauth2/token")))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                .body("""{"error":"invalid_client","error_description":"synthetic-private-value"}""")
                .header("PayPal-Debug-Id", "token-debug"))
        assertEquals(PaymentResult.Failed(PaymentStatus.FAILED, "token-debug", "invalid_client"),
            client.capture(AuthorizationId("AUTH1"), "key"))
        server.verify()
    }

    @Test
    fun `OAuth retries transient failures without starting the payment early`() {
        server.expect(requestTo(url("/v1/oauth2/token"))).andRespond(withServerError())
        server.expect(requestTo(url("/v1/oauth2/token"))).andRespond(withException(SocketTimeoutException()))
        token()
        expect(HttpMethod.GET, "/v2/payments/captures/CAP1", null, """{"id":"CAP1","status":"COMPLETED",$amount}""")
        assertEquals(PaymentStatus.COMPLETED, client.readCapture(CaptureId("CAP1"), "key").status)
        server.verify()
    }

    @Test
    fun `timeouts stop after four attempts and keep the last received debug id`() {
        token()
        server.expect(requestTo(url("/v2/payments/authorizations/AUTH1/capture")))
            .andExpect(header("PayPal-Request-Id", "stable-key"))
            .andRespond(withServerError().header("PayPal-Debug-Id", "before-timeout"))
        repeat(3) {
            server.expect(requestTo(url("/v2/payments/authorizations/AUTH1/capture")))
                .andExpect(header("PayPal-Request-Id", "stable-key"))
                .andRespond(withException(SocketTimeoutException()))
        }
        assertEquals(PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE, "before-timeout", "TRANSPORT_FAILURE"),
            client.capture(AuthorizationId("AUTH1"), "stable-key"))
        server.verify()
    }

    @Test
    fun `authorization and rate limit errors never trigger retries`() {
        token()
        for (code in listOf(HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND, HttpStatus.TOO_MANY_REQUESTS)) {
            server.expect(requestTo(url("/v2/payments/captures/CAP1"))).andRespond(withStatus(code))
        }
        for (code in listOf(400, 401, 403, 404, 429)) {
            assertEquals(PaymentResult.Failed(PaymentStatus.FAILED, errorCode = "HTTP_$code"),
                client.readCapture(CaptureId("CAP1"), "key"))
        }
        server.verify()
    }

    @Test
    fun `money conversion preserves large minor units and zero decimal currencies`() {
        token()
        val cases = listOf(Money(9007199254740993, "USD") to "90071992547409.93", Money(1234, "JPY") to "1234")
        for ((money, value) in cases) {
            server.expect(requestTo(url("/v2/checkout/orders")))
                .andExpect(content().json("""{"purchase_units":[{"amount":{"currency_code":"${money.currency}","value":"$value"}}]}"""))
                .andRespond(withSuccess("""{"id":"ORDER1","status":"CREATED"}""", MediaType.APPLICATION_JSON))
            expect(HttpMethod.GET, "/v2/payments/captures/CAP1", null,
                """{"id":"CAP1","status":"COMPLETED","amount":{"currency_code":"${money.currency}","value":"$value"}}""")
        }
        for ((money, _) in cases) {
            assertEquals(PaymentStatus.CREATED, client.createAuthorizeOrder(CreateAuthorizeOrder(UUID.randomUUID(), money), "key").status)
            assertEquals(money, received(client.readCapture(CaptureId("CAP1"), "key")).resource.amount)
        }
        server.verify()
    }

    @Test
    fun `redirects are failures and response links are never followed`() {
        token()
        server.expect(requestTo(url("/v2/payments/captures/CAP1")))
            .andRespond(withStatus(HttpStatus.FOUND).location(java.net.URI("https://api-m.paypal.com/v2/payments/captures/CAP1")))
        assertEquals(PaymentResult.Failed(PaymentStatus.FAILED, errorCode = "HTTP_302"), client.readCapture(CaptureId("CAP1"), "key"))
        server.verify()
    }

    @Test
    fun `resource ids cannot replace the sandbox host`() {
        token()
        expect(HttpMethod.GET, "/v2/payments/captures/https:%2F%2Fevil.example%2FCAP1", null,
            """{"id":"CAP1","status":"COMPLETED",$amount}""")
        assertEquals(PaymentStatus.COMPLETED, client.readCapture(CaptureId("https://evil.example/CAP1"), "key").status)
        server.verify()
    }

    @Test
    fun `malformed resource data fails without inferring money or status`() {
        token()
        for (body in listOf("{}", "not json", """{"id":"CAP1","status":"COMPLETED","amount":{"value":"1.234","currency_code":"USD"}}""")) {
            expect(HttpMethod.GET, "/v2/payments/captures/CAP1", null, body)
        }
        repeat(3) {
            assertEquals(PaymentResult.Failed(PaymentStatus.FAILED, "response-debug", "INVALID_RESPONSE"),
                client.readCapture(CaptureId("CAP1"), "key"))
        }
        server.verify()
    }

    @Test
    fun `unknown and cross resource statuses fail every response mapping`() {
        token()
        val calls = listOf<() -> PaymentResult<*>>(
            { client.createAuthorizeOrder(CreateAuthorizeOrder(UUID.randomUUID(), Money(1234, "USD")), "key") },
            { client.authorizeOrder(OrderId("ORDER1"), "key") },
            { client.readAuthorization(AuthorizationId("AUTH1"), "key") },
            { client.capture(AuthorizationId("AUTH1"), "key") },
            { client.readCapture(CaptureId("CAP1"), "key") },
            { client.voidAuthorization(AuthorizationId("AUTH1"), "key") },
            { client.refundCapture(CaptureId("CAP1"), "key") },
            { client.readRefund(RefundId("REF1"), "key") },
        )
        for (status in listOf("UNKNOWN", "TRANSPORT_FAILURE", "EXPIRED")) {
            val body = """{"id":"RESOURCE1","status":"$status",$amount}"""
            expect(HttpMethod.POST, "/v2/checkout/orders", "key", body)
            expect(HttpMethod.POST, "/v2/checkout/orders/ORDER1/authorize", "key",
                """{"purchase_units":[{"payments":{"authorizations":[$body]}}]}""")
            expect(HttpMethod.GET, "/v2/payments/authorizations/AUTH1", null, body)
            expect(HttpMethod.POST, "/v2/payments/authorizations/AUTH1/capture", "key", body)
            expect(HttpMethod.GET, "/v2/payments/captures/CAP1", null, body)
            expect(HttpMethod.POST, "/v2/payments/authorizations/AUTH1/void", "key", body)
            expect(HttpMethod.POST, "/v2/payments/captures/CAP1/refund", "key", body)
            expect(HttpMethod.GET, "/v2/payments/refunds/REF1", null, body)
        }
        repeat(3) {
            calls.forEach { call ->
                assertEquals(PaymentResult.Failed(PaymentStatus.FAILED, "response-debug", "INVALID_RESPONSE"), call())
            }
        }
        server.verify()
    }

    @Test
    fun `missing credentials allow startup and fail every operation without HTTP`() {
        val context = AnnotationConfigApplicationContext()
        context.environment = MockEnvironment()
        context.register(PayPalProperties::class.java, PayPalClient::class.java)
        context.refresh()
        assertNotNull(context.getBean(PaymentGateway::class.java))
        context.close()
        for ((id, secret) in listOf("" to "", "synthetic-client" to "", "" to "synthetic-secret")) {
            val unconfigured = PayPalClient(PayPalProperties(PayPalProperties.SANDBOX_URL, id, secret), builder, clock)
            val results = listOf(
                unconfigured.createAuthorizeOrder(CreateAuthorizeOrder(UUID.randomUUID(), Money(100, "USD")), "key"),
                unconfigured.authorizeOrder(OrderId("ORDER1"), "key"),
                unconfigured.readAuthorization(AuthorizationId("AUTH1"), "key"),
                unconfigured.capture(AuthorizationId("AUTH1"), "key"),
                unconfigured.voidAuthorization(AuthorizationId("AUTH1"), "key"),
                unconfigured.refundCapture(CaptureId("CAP1"), "key"),
                unconfigured.readCapture(CaptureId("CAP1"), "key"),
                unconfigured.readRefund(RefundId("REF1"), "key"),
            )
            results.forEach { assertEquals(PaymentResult.Failed(PaymentStatus.FAILED, errorCode = "NOT_CONFIGURED"), it) }
        }
        server.verify()
    }

    @Test
    fun `startup rejects every base URL other than the exact sandbox URL`() {
        for (base in listOf("https://api-m.paypal.com", "http://api-m.sandbox.paypal.com", "https://api-m.sandbox.paypal.com/",
            "https://api-m.sandbox.paypal.com.evil.example", "https://api-m.sandbox.paypal.com@evil.example", "", "HTTPS://api-m.sandbox.paypal.com")) {
            val context = AnnotationConfigApplicationContext()
            context.environment = MockEnvironment().withProperty("PAYPAL_BASE_URL", base)
            context.register(PayPalProperties::class.java, PayPalClient::class.java)
            val error = assertThrows(Exception::class.java) { context.refresh() }
            assertTrue(generateSequence(error as Throwable) { it.cause }.any {
                it.message?.contains("PAYPAL_BASE_URL must be exactly https://api-m.sandbox.paypal.com") == true
            })
            context.close()
        }
    }

    private fun token(expiresIn: Int = 3600) {
        server.expect(requestTo(url("/v1/oauth2/token"))).andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Basic c3ludGhldGljLWNsaWVudDpzeW50aGV0aWMtc2VjcmV0"))
            .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(content().string("grant_type=client_credentials"))
            .andRespond(withSuccess("""{"access_token":"synthetic-token","expires_in":$expiresIn}""", MediaType.APPLICATION_JSON)
                .header("PayPal-Debug-Id", "oauth-debug"))
    }

    private fun expect(method: HttpMethod, path: String, key: String?, body: String, requestBody: String? = null) {
        val expectation = server.expect(requestTo(url(path))).andExpect(method(method))
            .andExpect(header("Authorization", "Bearer synthetic-token"))
        if (key != null) expectation.andExpect(header("PayPal-Request-Id", key)).andExpect(header("Prefer", "return=representation"))
        else expectation.andExpect(headerDoesNotExist("PayPal-Request-Id"))
        if (requestBody != null) expectation.andExpect(content().json(requestBody))
        expectation.andRespond(withSuccess(body, MediaType.APPLICATION_JSON).header("PayPal-Debug-Id", "response-debug"))
    }

    private fun url(path: String) = PayPalProperties.SANDBOX_URL + path
    private fun <T> received(result: PaymentResult<T>): PaymentResult.Received<T> {
        assertInstanceOf(PaymentResult.Received::class.java, result)
        return result as PaymentResult.Received<T>
    }

    private class MutableClock(var now: Instant = Instant.parse("2026-11-12T00:00:00Z")) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}

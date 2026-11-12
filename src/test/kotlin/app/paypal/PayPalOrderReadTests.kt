package app.paypal

import app.campaign.model.Money
import app.campaign.port.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.*
import org.springframework.test.web.client.response.MockRestResponseCreators.*
import org.springframework.web.client.RestClient
import java.time.Clock

class PayPalOrderReadTests {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = PayPalClient(
        PayPalProperties(PayPalProperties.SANDBOX_URL, "synthetic-client", "synthetic-secret"), builder, Clock.systemUTC(),
    )

    @Test
    fun `order reads preserve documented raw statuses and their normalized mappings`() {
        token()
        val statuses = mapOf(
            "CREATED" to PaymentStatus.CREATED,
            "PAYER_ACTION_REQUIRED" to PaymentStatus.CREATED,
            "SAVED" to PaymentStatus.PENDING,
            "APPROVED" to PaymentStatus.APPROVED,
            "VOIDED" to PaymentStatus.VOIDED,
            "COMPLETED" to PaymentStatus.COMPLETED,
        )
        statuses.keys.forEach { order("""{"id":"ORDER1","status":"$it"}""") }
        statuses.forEach { (rawStatus, status) ->
            assertEquals(
                PaymentResult.Received(OrderDetails(OrderId("ORDER1"), rawStatus), status, "order-debug"),
                client.readOrder(OrderId("ORDER1"), "read-key"), rawStatus,
            )
        }
        server.verify()
    }

    @Test
    fun `order reads return payment references across purchase units without buyer fields`() {
        token()
        order("""{
            "id":"ORDER1","status":"COMPLETED","payer":{"email_address":"buyer@example.test"},
            "purchase_units":[
                {"amount":{"currency_code":"USD","value":"12.34"},
                    "shipping":{"name":{"full_name":"Synthetic Buyer"}},"payments":{
                    "authorizations":[{"id":"AUTH1","status":"CAPTURED"}],
                    "captures":[{"id":"CAP1","status":"PARTIALLY_REFUNDED"}],
                    "refunds":[{"id":"REF1","status":"COMPLETED"}]}},
                {"amount":{"currency_code":"USD","value":"5.00"},
                    "payments":{"authorizations":[{"id":"AUTH2","status":"VOIDED"}],
                    "captures":[{"id":"CAP2","status":"PENDING"}],
                    "refunds":[{"id":"REF2","status":"PENDING"}]}},
                {"reference_id":"no-payments"}
            ]
        }""")
        assertEquals(
            PaymentResult.Received(
                OrderDetails(OrderId("ORDER1"), "COMPLETED",
                    listOf(AuthorizationId("AUTH1"), AuthorizationId("AUTH2")),
                    listOf(CaptureId("CAP1"), CaptureId("CAP2")),
                    listOf(RefundId("REF1"), RefundId("REF2"))),
                PaymentStatus.COMPLETED, "order-debug",
            ),
            client.readOrder(OrderId("ORDER1"), "read-key"),
        )
        server.verify()
    }

    @Test
    fun `completed order does not replace the capture or refund resource status`() {
        token()
        order("""{"id":"ORDER1","status":"COMPLETED","purchase_units":[{"payments":{
            "captures":[{"id":"CAP1","status":"PARTIALLY_REFUNDED"}],
            "refunds":[{"id":"REF1","status":"PENDING"}]}}]}""")
        read("/v2/payments/captures/CAP1", """{"id":"CAP1","status":"PARTIALLY_REFUNDED",
            "amount":{"currency_code":"USD","value":"12.34"}}""")
        read("/v2/payments/refunds/REF1", """{"id":"REF1","status":"PENDING",
            "amount":{"currency_code":"USD","value":"1.00"}}""")
        val order = client.readOrder(OrderId("ORDER1"), "read-key") as PaymentResult.Received
        val capture = client.readCapture(order.resource.captureIds.single(), "read-key") as PaymentResult.Received
        val refund = client.readRefund(order.resource.refundIds.single(), "read-key") as PaymentResult.Received
        assertEquals(PaymentStatus.COMPLETED, order.status)
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, capture.status)
        assertEquals(Money(1234, "USD"), capture.resource.amount)
        assertEquals(PaymentStatus.PENDING, refund.status)
        assertEquals(Money(100, "USD"), refund.resource.amount)
        server.verify()
    }

    @Test
    fun `single purchase unit amount uses exact integer minor units`() {
        token()
        order("""{"id":"ORDER1","status":"APPROVED","purchase_units":[{
            "amount":{"currency_code":"USD","value":"90071992547409.93"}}]}""")
        val result = client.readOrder(OrderId("ORDER1"), "read-key") as PaymentResult.Received
        assertEquals(Money(9007199254740993, "USD"), result.resource.amount)
        server.verify()
    }

    @Test
    fun `invalid order or nested payment references remain failed reads`() {
        token()
        val bodies = listOf(
            "{}", "not json", """{"id":"ORDER1","status":"CAPTURED"}""",
            """{"id":"ORDER1","status":"COMPLETED","purchase_units":[{"payments":{"captures":[{}]}}]}""",
            """{"id":"ORDER1","status":"COMPLETED","purchase_units":{}}""",
            """{"id":"ORDER1","status":"COMPLETED","purchase_units":[null]}""",
            """{"id":"ORDER1","status":"COMPLETED","purchase_units":[{"payments":[]}]}""",
            """{"id":"ORDER1","status":"COMPLETED","purchase_units":[{"payments":{"captures":{}}}]}""",
            """{"id":"ORDER1","status":"COMPLETED","purchase_units":[{"amount":{"currency_code":"USD","value":"1.234"}}]}""",
        )
        bodies.forEach { order(it) }
        bodies.forEach { _ ->
            assertEquals(PaymentResult.Failed(PaymentStatus.FAILED, "order-debug", "INVALID_RESPONSE"),
                client.readOrder(OrderId("ORDER1"), "read-key"))
        }
        server.verify()
    }

    @Test
    fun `order reads encode resource identifiers and do not follow response links`() {
        token()
        read("/v2/checkout/orders/https:%2F%2Fevil.example%2FORDER1", """{
            "id":"ORDER1","status":"CREATED",
            "links":[{"href":"https://evil.example/private","rel":"self"}]
        }""")
        assertEquals(PaymentStatus.CREATED,
            client.readOrder(OrderId("https://evil.example/ORDER1"), "read-key").status)
        server.verify()
    }

    @Test
    fun `missing orders stay unknown rather than returning order details`() {
        token()
        server.expect(requestTo(PayPalProperties.SANDBOX_URL + "/v2/checkout/orders/ORDER1"))
            .andExpect(method(HttpMethod.GET)).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .header("PayPal-Debug-Id", "missing-debug"))
        assertEquals(PaymentResult.Failed(PaymentStatus.FAILED, "missing-debug", "HTTP_404"),
            client.readOrder(OrderId("ORDER1"), "read-key"))
        server.verify()
    }

    @Test
    fun `unconfigured order reads do not send HTTP requests`() {
        val unconfigured = PayPalClient(PayPalProperties(PayPalProperties.SANDBOX_URL, "", ""), builder, Clock.systemUTC())
        assertEquals(PaymentResult.Failed(PaymentStatus.FAILED, errorCode = "NOT_CONFIGURED"),
            unconfigured.readOrder(OrderId("ORDER1"), "read-key"))
        server.verify()
    }

    private fun token() {
        server.expect(requestTo(PayPalProperties.SANDBOX_URL + "/v1/oauth2/token"))
            .andRespond(withSuccess("""{"access_token":"synthetic-token","expires_in":3600}""", MediaType.APPLICATION_JSON))
    }

    private fun order(body: String) = read("/v2/checkout/orders/ORDER1", body)

    private fun read(path: String, body: String) {
        server.expect(requestTo(PayPalProperties.SANDBOX_URL + path)).andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer synthetic-token"))
            .andExpect(headerDoesNotExist("PayPal-Request-Id"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON).header("PayPal-Debug-Id", "order-debug"))
    }
}

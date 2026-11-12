package app.paypal

import app.campaign.port.WebhookVerification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.*
import org.springframework.test.web.client.response.MockRestResponseCreators.*
import org.springframework.web.client.RestClient
import java.time.Clock
import org.springframework.mock.http.client.MockClientHttpRequest

class PayPalWebhookVerificationTests {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = PayPalClient(
        PayPalProperties(PayPalProperties.SANDBOX_URL, "synthetic-client", "synthetic-secret"), builder, Clock.systemUTC(),
    )
    private val endpoint = PayPalProperties.SANDBOX_URL + "/v1/notifications/verify-webhook-signature"
    private val headers = mapOf(
        "paypal-auth-algo" to "SHA256withRSA",
        "paypal-cert-url" to "https://api.sandbox.paypal.com/v1/notifications/certs/SYNTHETIC",
        "paypal-transmission-id" to "synthetic-transmission",
        "paypal-transmission-sig" to "synthetic-signature",
        "paypal-transmission-time" to "2026-11-12T00:00:00Z",
    )
    private val event = """{"id":"WH-SYNTHETIC","event_type":"PAYMENT.CAPTURE.COMPLETED","resource":{"id":"CAPTURE1","status":"COMPLETED"}}"""

    @Test
    fun `verification sends all documented fields and the complete event as an object`() {
        token()
        server.expect(requestTo(endpoint)).andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer synthetic-token"))
            .andExpect(headerDoesNotExist("PayPal-Request-Id"))
            .andExpect(content().json("""{
                "auth_algo":"SHA256withRSA",
                "cert_url":"https://api.sandbox.paypal.com/v1/notifications/certs/SYNTHETIC",
                "transmission_id":"synthetic-transmission",
                "transmission_sig":"synthetic-signature",
                "transmission_time":"2026-11-12T00:00:00Z",
                "webhook_id":"SYNTHETICHOOK",
                "webhook_event":$event
            }"""))
            .andRespond(withSuccess("""{"verification_status":"SUCCESS"}""", MediaType.APPLICATION_JSON))
        assertEquals(WebhookVerification.VERIFIED, client.verifyWebhook("SYNTHETICHOOK", headers, event))
        server.verify()
    }

    @Test
    fun `provider signature failure rejects the event`() {
        token()
        verificationResponse("""{"verification_status":"FAILURE"}""")
        assertEquals(WebhookVerification.INVALID, client.verifyWebhook("SYNTHETICHOOK", headers, event))
        server.verify()
    }

    @Test
    fun `verification preserves original event JSON whitespace and numeric formatting`() {
        token()
        val original = "  { \"id\" : \"WH-SYNTHETIC\", \"value\" : 1.00, \"escaped\" : \"\\u0061\" }\n"
        server.expect(requestTo(endpoint)).andExpect { request ->
            assertTrue((request as MockClientHttpRequest).bodyAsString.endsWith("\"webhook_event\":" + original + "}"))
        }.andRespond(withSuccess("""{"verification_status":"SUCCESS"}""", MediaType.APPLICATION_JSON))
        assertEquals(WebhookVerification.VERIFIED, client.verifyWebhook("SYNTHETICHOOK", headers, original))
        server.verify()
    }

    @Test
    fun `unknown or malformed verification responses never verify`() {
        token()
        for (body in listOf("""{"verification_status":"PENDING"}""", "{}", "not-json")) verificationResponse(body)
        repeat(3) {
            assertEquals(WebhookVerification.UNAVAILABLE, client.verifyWebhook("SYNTHETICHOOK", headers, event))
        }
        server.verify()
    }

    @Test
    fun `missing signature metadata and invalid events are rejected without a provider call`() {
        for (header in headers.keys) {
            assertEquals(WebhookVerification.INVALID, client.verifyWebhook("SYNTHETICHOOK", headers - header, event))
            assertEquals(WebhookVerification.INVALID, client.verifyWebhook("SYNTHETICHOOK", headers + (header to ""), event))
        }
        for (body in listOf("not-json", "[]", "null", "{} {}", "{},\"webhook_id\":\"OTHER\"")) {
            assertEquals(WebhookVerification.INVALID, client.verifyWebhook("SYNTHETICHOOK", headers, body))
        }
        server.verify()
    }

    @Test
    fun `missing webhook id or credentials cannot verify and makes no provider call`() {
        assertEquals(WebhookVerification.UNAVAILABLE, client.verifyWebhook("", headers, event))
        val unconfigured = PayPalClient(PayPalProperties(PayPalProperties.SANDBOX_URL, "", ""), builder, Clock.systemUTC())
        assertEquals(WebhookVerification.UNAVAILABLE, unconfigured.verifyWebhook("SYNTHETICHOOK", headers, event))
        server.verify()
    }

    @Test
    fun `provider outages are bounded and remain unavailable`() {
        token()
        repeat(4) { server.expect(requestTo(endpoint)).andRespond(withServerError()) }
        assertEquals(WebhookVerification.UNAVAILABLE, client.verifyWebhook("SYNTHETICHOOK", headers, event))
        server.verify()
    }

    @Test
    fun `provider client error does not retry or accept an event`() {
        token()
        server.expect(requestTo(endpoint)).andRespond(withStatus(HttpStatus.BAD_REQUEST))
        assertEquals(WebhookVerification.UNAVAILABLE, client.verifyWebhook("SYNTHETICHOOK", headers, event))
        server.verify()
    }

    private fun token() {
        server.expect(requestTo(PayPalProperties.SANDBOX_URL + "/v1/oauth2/token"))
            .andRespond(withSuccess("""{"access_token":"synthetic-token","expires_in":3600}""", MediaType.APPLICATION_JSON))
    }

    private fun verificationResponse(body: String) {
        server.expect(requestTo(endpoint)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
    }
}

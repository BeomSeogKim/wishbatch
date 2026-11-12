package app.paypal

import app.campaign.model.Money
import app.campaign.model.ShippingAddress
import app.campaign.port.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriUtils
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Currency

@Component
class PayPalClient internal constructor(
    private val properties: PayPalProperties,
    builder: RestClient.Builder,
    private val clock: Clock,
) : PaymentGateway {
    @Autowired
    constructor(properties: PayPalProperties) : this(properties,
        RestClient.builder().requestFactory(sandboxRequestFactory()), Clock.systemUTC())

    private val http = builder.build()
    private val json = JsonMapper.builder().build()
    private val logger = LoggerFactory.getLogger(PayPalClient::class.java)
    private var token: String? = null
    private var refreshAt: Instant = Instant.MIN

    override fun createAuthorizeOrder(request: CreateAuthorizeOrder, idempotencyKey: String) =
        call(HttpMethod.POST, "/v2/checkout/orders", idempotencyKey, mapOf(
            "intent" to "AUTHORIZE",
            "purchase_units" to listOf(mapOf("custom_id" to request.pledgeId.toString(), "amount" to mapOf(
                "currency_code" to request.amount.currency,
                "value" to BigDecimal.valueOf(request.amount.minorUnits, scale(request.amount.currency)).toPlainString(),
            ))),
            "payment_source" to mapOf("paypal" to mapOf("experience_context" to mapOf("shipping_preference" to "GET_FROM_FILE"))),
        )) { body -> OrderDetails(OrderId(body.requiredText("id"))) to orderStatus(body) }

    override fun authorizeOrder(orderId: OrderId, idempotencyKey: String) =
        call(HttpMethod.POST, "/v2/checkout/orders/${segment(orderId.value)}/authorize", idempotencyKey, emptyMap<String, Any>()) { body ->
            val unit = body.path("purchase_units").path(0)
            val authorization = unit.path("payments").path("authorizations").path(0)
            authorization(authorization, orderId, body.path("payer").text("email_address"), shipping(unit.path("shipping")))
        }

    override fun readAuthorization(authorizationId: AuthorizationId, idempotencyKey: String) =
        call(HttpMethod.GET, "/v2/payments/authorizations/${segment(authorizationId.value)}", idempotencyKey) { body ->
            authorization(body)
        }

    override fun capture(authorizationId: AuthorizationId, idempotencyKey: String) =
        call(HttpMethod.POST, "/v2/payments/authorizations/${segment(authorizationId.value)}/capture", idempotencyKey,
            mapOf("final_capture" to true)) { body -> capture(body, authorizationId) }

    override fun voidAuthorization(authorizationId: AuthorizationId, idempotencyKey: String) =
        call(HttpMethod.POST, "/v2/payments/authorizations/${segment(authorizationId.value)}/void", idempotencyKey,
            emptyMap<String, Any>(), emptyResponse = VoidDetails(authorizationId) to PaymentStatus.VOIDED,
        ) { body -> VoidDetails(authorizationId) to authorizationStatus(body) }

    override fun refundCapture(captureId: CaptureId, idempotencyKey: String) =
        call(HttpMethod.POST, "/v2/payments/captures/${segment(captureId.value)}/refund", idempotencyKey,
            emptyMap<String, Any>()) { body -> refund(body, captureId) }

    override fun readCapture(captureId: CaptureId, idempotencyKey: String) =
        call(HttpMethod.GET, "/v2/payments/captures/${segment(captureId.value)}", idempotencyKey) { body -> capture(body) }

    override fun readRefund(refundId: RefundId, idempotencyKey: String) =
        call(HttpMethod.GET, "/v2/payments/refunds/${segment(refundId.value)}", idempotencyKey) { body -> refund(body) }

    override fun verifyWebhook(webhookId: String, headers: Map<String, String>, eventJson: String): WebhookVerification {
        if (webhookId.isBlank()) return WebhookVerification.UNAVAILABLE
        val transmissionFields = mapOf(
            "auth_algo" to "paypal-auth-algo",
            "cert_url" to "paypal-cert-url",
            "transmission_id" to "paypal-transmission-id",
            "transmission_sig" to "paypal-transmission-sig",
            "transmission_time" to "paypal-transmission-time",
        )
        val request = mutableMapOf<String, Any>("webhook_id" to webhookId)
        for ((field, header) in transmissionFields) {
            request[field] = headers[header]?.takeIf { it.isNotBlank() } ?: return WebhookVerification.INVALID
        }
        val event = try {
            json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(eventJson)
        } catch (_: RuntimeException) { return WebhookVerification.INVALID }
        if (event == null || !event.isObject) return WebhookVerification.INVALID
        // PayPal requires the event JSON without parsing and serialization changes.
        val requestJson = json.writeValueAsString(request).dropLast(1) + ",\"webhook_event\":" + eventJson + "}"
        val result = call(HttpMethod.POST, "/v1/notifications/verify-webhook-signature", null, RawJson(requestJson)) { body ->
            when (body.requiredText("verification_status")) {
                "SUCCESS" -> WebhookVerification.VERIFIED
                "FAILURE" -> WebhookVerification.INVALID
                else -> error("Unexpected verification status")
            } to PaymentStatus.CREATED
        }
        return if (result is PaymentResult.Received) result.resource else WebhookVerification.UNAVAILABLE
    }

    private fun authorization(body: JsonNode, orderId: OrderId? = null, email: String? = null, address: ShippingAddress? = null) =
        AuthorizationDetails(
            AuthorizationId(body.requiredText("id")), money(body),
            body.text("expiration_time")?.let(Instant::parse),
            orderId ?: body.path("supplementary_data").path("related_ids").text("order_id")?.let(::OrderId),
            email, address,
            body.path("supplementary_data").path("related_ids").text("capture_id")?.let(::CaptureId),
        ) to authorizationStatus(body)

    private fun capture(body: JsonNode, authorizationId: AuthorizationId? = null) = CaptureDetails(
        CaptureId(body.requiredText("id")), money(body),
        authorizationId ?: body.path("supplementary_data").path("related_ids").text("authorization_id")?.let(::AuthorizationId),
    ) to captureStatus(body)

    private fun refund(body: JsonNode, captureId: CaptureId? = null) = RefundDetails(
        RefundId(body.requiredText("id")), money(body),
        captureId ?: body.path("supplementary_data").path("related_ids").text("capture_id")?.let(::CaptureId),
    ) to refundStatus(body)

    private fun shipping(body: JsonNode): ShippingAddress? {
        if (body.isMissingNode || body.isNull) return null
        val address = body.path("address")
        return ShippingAddress(body.path("name").text("full_name"), address.text("address_line_1"),
            address.text("address_line_2"), address.text("admin_area_1"), address.text("admin_area_2"),
            address.text("postal_code"), address.text("country_code"))
    }

    private fun money(body: JsonNode): Money {
        val amount = body.path("amount")
        val currency = amount.requiredText("currency_code")
        return Money(BigDecimal(amount.requiredText("value")).movePointRight(scale(currency)).longValueExact(), currency)
    }

    private fun scale(currency: String) = Currency.getInstance(currency).defaultFractionDigits.also { require(it >= 0) }
    private fun segment(value: String) = UriUtils.encodePathSegment(value, UTF_8)
    private fun JsonNode.text(field: String): String? = path(field).let { if (it.isString) it.asString() else null }
    private fun JsonNode.requiredText(field: String) = requireNotNull(text(field))
    private fun orderStatus(body: JsonNode) = when (body.requiredText("status")) {
        "CREATED", "PAYER_ACTION_REQUIRED" -> PaymentStatus.CREATED
        "SAVED" -> PaymentStatus.PENDING
        "APPROVED" -> PaymentStatus.APPROVED
        "VOIDED" -> PaymentStatus.VOIDED
        "COMPLETED" -> PaymentStatus.COMPLETED
        else -> error("Unexpected order status")
    }

    private fun authorizationStatus(body: JsonNode) = when (body.requiredText("status")) {
        "CREATED" -> PaymentStatus.CREATED
        "CAPTURED", "PARTIALLY_CAPTURED" -> PaymentStatus.CAPTURED
        "DENIED" -> PaymentStatus.DECLINED
        "VOIDED" -> PaymentStatus.VOIDED
        "PENDING" -> PaymentStatus.PENDING
        else -> error("Unexpected authorization status")
    }

    private fun captureStatus(body: JsonNode) = when (body.requiredText("status")) {
        "COMPLETED" -> PaymentStatus.COMPLETED
        "DECLINED" -> PaymentStatus.DECLINED
        "PARTIALLY_REFUNDED" -> PaymentStatus.PARTIALLY_REFUNDED
        "PENDING" -> PaymentStatus.PENDING
        "REFUNDED" -> PaymentStatus.REFUNDED
        "FAILED" -> PaymentStatus.FAILED
        else -> error("Unexpected capture status")
    }

    private fun refundStatus(body: JsonNode) = when (body.requiredText("status")) {
        "CANCELLED", "FAILED" -> PaymentStatus.FAILED
        "PENDING" -> PaymentStatus.PENDING
        "COMPLETED" -> PaymentStatus.COMPLETED
        else -> error("Unexpected refund status")
    }

    private fun <T> call(
        method: HttpMethod, path: String, key: String?, body: Any? = null,
        emptyResponse: Pair<T, PaymentStatus>? = null,
        parse: (JsonNode) -> Pair<T, PaymentStatus>,
    ): PaymentResult<T> {
        if (!properties.configured) return PaymentResult.Failed(PaymentStatus.FAILED, errorCode = "NOT_CONFIGURED")
        val access = accessToken()
        if (access is PaymentResult.Failed) return access
        val response = exchange(method, path, key, body, (access as PaymentResult.Received).resource)
        if (response is PaymentResult.Failed) return response
        response as PaymentResult.Received
        val received = response.resource
        if (received.code !in 200..299) return failure(received)
        if (received.code == 204 && emptyResponse != null) {
            return PaymentResult.Received(emptyResponse.first, emptyResponse.second, received.debugId)
        }
        return try {
            val (resource, status) = parse(if (received.body.isBlank()) json.createObjectNode() else json.readTree(received.body))
            PaymentResult.Received(resource, status, received.debugId)
        } catch (_: RuntimeException) {
            PaymentResult.Failed(PaymentStatus.FAILED, received.debugId, "INVALID_RESPONSE")
        }
    }

    @Synchronized
    private fun accessToken(): PaymentResult<String> {
        token?.let { if (clock.instant().isBefore(refreshAt)) return PaymentResult.Received(it, PaymentStatus.CREATED) }
        val startedAt = clock.instant()
        val result = exchange(HttpMethod.POST, "/v1/oauth2/token", null, "grant_type=client_credentials", null)
        if (result is PaymentResult.Failed) return result
        result as PaymentResult.Received
        val response = result.resource
        if (response.code !in 200..299) return failure(response)
        return try {
            val body = json.readTree(response.body)
            val value = body.requiredText("access_token")
            val expiresIn = body.path("expires_in").asLong()
            require(value.isNotBlank() && expiresIn > 0)
            refreshAt = startedAt.plusSeconds((expiresIn - 30).coerceAtLeast(0))
            token = value
            PaymentResult.Received(value, PaymentStatus.CREATED, response.debugId)
        } catch (_: RuntimeException) {
            PaymentResult.Failed(PaymentStatus.FAILED, response.debugId, "INVALID_TOKEN_RESPONSE")
        }
    }

    private data class Response(val code: Int, val debugId: String?, val body: String)
    private data class RawJson(val value: String)

    private fun exchange(method: HttpMethod, path: String, key: String?, body: Any?, bearer: String?): PaymentResult<Response> {
        var lastDebugId: String? = null
        repeat(4) { attempt ->
            try {
                val request = http.method(method).uri(URI.create(PayPalProperties.SANDBOX_URL + path))
                    .headers { headers ->
                        headers.accept = listOf(MediaType.APPLICATION_JSON)
                        if (bearer == null) headers.setBasicAuth(properties.clientId, properties.clientSecret, UTF_8)
                        else headers.setBearerAuth(bearer)
                        if (key != null && method == HttpMethod.POST) {
                            headers.set("PayPal-Request-Id", key)
                            headers.set("Prefer", "return=representation")
                        }
                    }
                if (body != null) {
                    request.contentType(if (bearer == null) MediaType.APPLICATION_FORM_URLENCODED else MediaType.APPLICATION_JSON)
                        .body(if (bearer == null) body else if (body is RawJson) body.value else json.writeValueAsString(body))
                }
                val response = request.exchange { _, response ->
                    val debugId = response.headers.getFirst("PayPal-Debug-Id")
                    logger.info("PayPal method={} endpoint={} status={} debugId={}", method, path, response.statusCode.value(),
                        debugId?.replace(Regex("[^A-Za-z0-9_-]"), "_"))
                    lastDebugId = debugId
                    Response(response.statusCode.value(), debugId, response.body.readAllBytes().toString(UTF_8))
                }!!
                if (response.code < 500 || attempt == 3) {
                    return if (response.code >= 500) PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE, response.debugId, "HTTP_${response.code}")
                    else PaymentResult.Received(response, PaymentStatus.CREATED, response.debugId)
                }
            } catch (_: ResourceAccessException) {
                if (attempt == 3) return PaymentResult.Failed(PaymentStatus.TRANSPORT_FAILURE, lastDebugId, "TRANSPORT_FAILURE")
            }
        }
        error("Unreachable retry state")
    }

    private fun failure(response: Response): PaymentResult.Failed {
        val issue = try {
            val body = json.readTree(response.body)
            body.path("details").path(0).text("issue") ?: body.text("name") ?: body.text("error")
        } catch (_: RuntimeException) { null }
        val status = when (issue) {
            "AUTHORIZATION_ALREADY_CAPTURED" -> PaymentStatus.ALREADY_CAPTURED
            "AUTHORIZATION_VOIDED" -> PaymentStatus.VOIDED
            "AUTHORIZATION_EXPIRED" -> PaymentStatus.EXPIRED
            "INSTRUMENT_DECLINED", "PAYMENT_DENIED", "TRANSACTION_REFUSED", "PAYER_CANNOT_PAY", "CARD_DECLINED" -> PaymentStatus.DECLINED
            else -> PaymentStatus.FAILED
        }
        return PaymentResult.Failed(status, response.debugId, issue ?: "HTTP_${response.code}")
    }

    companion object {
        private fun sandboxRequestFactory(): ClientHttpRequestFactory {
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build()
            return JdkClientHttpRequestFactory(client).apply { setReadTimeout(Duration.ofSeconds(20)) }
        }
    }
}

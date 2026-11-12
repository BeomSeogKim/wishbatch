package app.web.webhook

import app.campaign.webhook.PaymentWebhook
import app.campaign.webhook.PaymentWebhookService
import app.campaign.webhook.WebhookResult
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.util.Locale

@RestController
class PayPalWebhookController(private val service: PaymentWebhookService, private val mapper: ObjectMapper) {
    @PostMapping("/webhooks/paypal", consumes = ["application/json"])
    fun receive(@RequestBody body: String, @RequestHeader headers: HttpHeaders): ResponseEntity<Void> {
        val event = try {
            val json = mapper.readTree(body)
            val resource = json.path("resource")
            PaymentWebhook(required(json, "id", 255), required(json, "event_type", 100),
                required(json, "resource_type", 40), required(resource, "id", 255),
                resource.path("supplementary_data").path("related_ids").path("capture_id").optionalText()
                    ?: resource.path("links").firstOrNull { it.path("rel").asString() == "up" }
                        ?.path("href")?.optionalText()?.let(::captureId)).also {
                    require(it.captureId == null || (it.captureId.isNotBlank() && it.captureId.length <= 255))
                }
        } catch (_: JacksonException) {
            return ResponseEntity.badRequest().build()
        } catch (_: IllegalArgumentException) {
            return ResponseEntity.badRequest().build()
        }
        val result = service.receive(event, headers.toSingleValueMap().mapKeys { it.key.lowercase(Locale.ROOT) }, body)
        return ResponseEntity.status(when (result) {
            WebhookResult.ACCEPTED -> HttpStatus.OK
            WebhookResult.INVALID_SIGNATURE -> HttpStatus.UNAUTHORIZED
            WebhookResult.UNAVAILABLE, WebhookResult.DISABLED -> HttpStatus.SERVICE_UNAVAILABLE
        }).build()
    }

    private fun required(node: JsonNode, name: String, limit: Int): String =
        requireNotNull(node.path(name).optionalText()).also { require(it.isNotBlank() && it.length <= limit) }

    private fun JsonNode.optionalText(): String? = if (isString) asString() else null

    private fun captureId(link: String): String? {
        val uri = URI.create(link)
        if (uri.scheme != "https" || uri.host !in setOf("api-m.sandbox.paypal.com", "api.sandbox.paypal.com")) return null
        return Regex("/v2/payments/captures/([A-Za-z0-9_-]{1,255})").matchEntire(uri.path)?.groupValues?.get(1)
    }
}

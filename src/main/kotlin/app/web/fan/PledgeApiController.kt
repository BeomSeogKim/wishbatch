package app.web.fan

import app.campaign.model.PledgeState
import app.campaign.service.CampaignQueries
import app.campaign.service.CampaignRef
import app.campaign.service.CampaignService
import app.campaign.service.PledgeReferences
import app.campaign.service.PledgeView
import app.campaign.service.Rejection
import app.campaign.service.ServiceResult
import app.campaign.workspace.WorkspaceService
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.servlet.http.HttpSession
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.http.converter.HttpMessageNotReadableException
import java.util.Locale
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class StartPledgeRequest(val quantity: Int, val campaignId: UUID? = null, val wishId: UUID? = null)

enum class PledgeOutcome { CREATED, AUTHORIZED, REJECTED }

data class PledgeResponse(
    val outcome: PledgeOutcome,
    val message: String,
    val reason: Rejection? = null,
    val pledgeId: UUID? = null,
    val orderId: String? = null,
    val state: PledgeState? = null,
    val statusUrl: String? = null,
)

@RestController
class PledgeApiController(
    private val workspaces: WorkspaceService,
    private val queries: CampaignQueries,
    private val references: PledgeReferences,
    private val campaigns: CampaignService,
    private val messages: MessageSource,
) {
    @PostMapping("/w/{token}/api/pledges")
    fun start(@PathVariable token: String, @RequestBody request: StartPledgeRequest, session: HttpSession): ResponseEntity<PledgeResponse> {
        val workspace = workspaces.findByToken(token) ?: return rejected(token, ServiceResult.Rejected(Rejection.NOT_FOUND))
        val quote = when (val result = queries.quote(token, request.quantity)) {
            is ServiceResult.Success -> result.value
            is ServiceResult.Rejected -> return rejected(token, result)
        }
        return when (val result = campaigns.startPledge(
            CampaignRef(workspace.id, request.campaignId ?: quote.campaignId), request.quantity,
            request.wishId ?: session.getAttribute("followup.wish.$token") as? UUID,
        )) {
            is ServiceResult.Success -> ResponseEntity.status(201).body(
                payload(token, PledgeOutcome.CREATED, result.value, "Approve in PayPal to request a hold. No pledge has counted yet."))
            is ServiceResult.Rejected -> rejected(token, result)
        }
    }

    @PostMapping("/w/{token}/api/pledges/{id}/authorize")
    fun authorize(@PathVariable token: String, @PathVariable id: String): ResponseEntity<PledgeResponse> {
        if (workspaces.findByToken(token) == null) return rejected(token, ServiceResult.Rejected(Rejection.NOT_FOUND))
        val pledgeId = parsePledgeId(id) ?: return rejected(token, ServiceResult.Rejected(Rejection.NOT_FOUND))
        val ref = when (val result = references.find(token, pledgeId)) {
            is ServiceResult.Success -> result.value
            is ServiceResult.Rejected -> return rejected(token, result)
        }
        return when (val result = campaigns.completePledge(ref)) {
            is ServiceResult.Success -> ResponseEntity.ok(payload(token, PledgeOutcome.AUTHORIZED, result.value,
                pledgeSentence(result.value.state, messages)))
            is ServiceResult.Rejected -> rejected(token, result)
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun invalidRequest(): ResponseEntity<PledgeResponse> = ResponseEntity.badRequest().body(
        PledgeResponse(PledgeOutcome.REJECTED, "Choose a whole-number quantity within the offer's limit.", Rejection.INVALID_QUANTITY))

    private fun rejected(token: String, result: ServiceResult.Rejected): ResponseEntity<PledgeResponse> {
        val status = when (result.reason) {
            Rejection.NOT_FOUND -> 404
            Rejection.INVALID_QUANTITY, Rejection.AMOUNT_OVERFLOW -> 400
            Rejection.PAYMENT_UNAVAILABLE -> 503
            else -> 409
        }
        val explanation = when (result.reason) {
            Rejection.NOT_FOUND -> "This pledge or workspace could not be found."
            Rejection.INVALID_QUANTITY, Rejection.AMOUNT_OVERFLOW -> "Choose a quantity within this offer's limit."
            Rejection.CAPACITY_EXCEEDED -> "There is not enough capacity for this pledge."
            Rejection.INVALID_STATE -> "This offer is no longer accepting this pledge."
            Rejection.PAYMENT_MISMATCH -> "PayPal's authorization did not match the offer."
            else -> "PayPal could not confirm this request."
        }
        val detail = result.pledge?.let { pledgeSentence(it.state, messages) }
            ?: "No pledge counted. You can return to the offer and try again."
        return ResponseEntity.status(status).body(PledgeResponse(
            PledgeOutcome.REJECTED, "$explanation $detail", result.reason, result.pledge?.id,
            state = result.pledge?.state, statusUrl = result.pledge?.let { "/w/$token/pledges/${it.id}" },
        ))
    }

    private fun payload(token: String, outcome: PledgeOutcome, pledge: PledgeView, message: String) =
        PledgeResponse(outcome, message, pledgeId = pledge.id, orderId = pledge.orderId,
            state = pledge.state, statusUrl = "/w/$token/pledges/${pledge.id}")
}

internal fun parsePledgeId(value: String): UUID? = try {
    UUID.fromString(value).takeIf { it.toString().equals(value, ignoreCase = true) }
} catch (_: IllegalArgumentException) { null }

internal fun pledgeSentence(state: PledgeState, messages: MessageSource): String = messages.getMessage(
    "pledge.${state.name}", null, when (state) {
        PledgeState.CREATED -> "Authorization is not confirmed. This pledge has not counted."
        PledgeState.ABANDONED -> "This pledge was not completed and has not counted."
        PledgeState.VOID_FAILED -> "The hold release is not confirmed. This pledge needs attention."
        PledgeState.REFUND_FAILED -> "The refund is not confirmed. This pledge needs attention."
        else -> state.name
    }, Locale.ENGLISH,
)!!

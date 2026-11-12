package app.web.layout

import app.campaign.model.Campaign
import app.campaign.model.CampaignState
import app.campaign.rules.authorizedUnits
import app.campaign.store.CampaignRepository
import app.campaign.store.OfferRepository
import app.campaign.store.PledgeRepository
import app.campaign.store.WorkspaceRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Instant
import java.util.UUID

internal data class DemoGuide(
    val location: String,
    val next: String,
    val nextUrl: String?,
    val switchLabel: String,
    val switchUrl: String,
    val explanationUrl: String?,
    val decisionRecordUrl: String?,
    val makerView: Boolean,
)

@Configuration
class DemoGuideConfiguration(
    private val workspaces: ObjectProvider<WorkspaceRepository>,
    private val campaigns: ObjectProvider<CampaignRepository>,
    private val offers: ObjectProvider<OfferRepository>,
    private val pledges: ObjectProvider<PledgeRepository>,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(guideInterceptor())
    }

    internal fun guideInterceptor(): HandlerInterceptor = object : HandlerInterceptor {
        override fun postHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any, modelAndView: ModelAndView?) {
            if (request.method != "GET" || response.status != 200 || modelAndView == null ||
                modelAndView.viewName?.startsWith("redirect:") != false) return
            val match = Regex("^/w/([A-Za-z0-9_-]{43})(?:/(.*))?$")
                .matchEntire(request.requestURI.removePrefix(request.contextPath)) ?: return
            val token = match.groupValues[1]
            val path = match.groupValues[2].trimEnd('/')
            val workspace = workspaces.ifAvailable?.findByToken(token) ?: return
            val records = campaigns.ifAvailable?.findByWorkspaceId(workspace.id) ?: return
            val order = compareByDescending<Campaign> { it.openedAt ?: Instant.MIN }.thenByDescending { it.id }
            val campaign = records.filter { it.state in activeStates }.sortedWith(order).firstOrNull()
                ?: records.filter { it.state in terminalStates }.sortedWith(order).firstOrNull()
            val offer = campaign?.let { offers.ifAvailable?.findByIdAndWorkspaceId(it.offerId, workspace.id) }
            val campaignPledges = campaign?.takeIf { offer != null }?.let {
                pledges.ifAvailable?.findByCampaignIdOrderByCreatedAtAscIdAsc(it.id)
            }.orEmpty()
            val minimumMet = offer?.let { authorizedUnits(campaignPledges) >= it.minBatch } ?: false
            modelAndView.addObject("demoGuide", guideFor(token, path, campaign?.state, minimumMet,
                modelAndView.model["proposalUnavailable"] == true, modelAndView.model["makerStep"] as? Int,
                modelAndView.model["wishSubmitted"] == true || modelAndView.model["lastWishId"] != null,
                campaign?.id, campaignPledges.lastOrNull()?.id))
        }
    }
}

private val activeStates = setOf(CampaignState.OPEN, CampaignState.AWAITING_CONFIRMATION, CampaignState.SETTLING)
private val terminalStates = setOf(CampaignState.FUNDED, CampaignState.NOT_FUNDED)

internal fun guideFor(
    token: String, path: String, state: CampaignState?, minimumMet: Boolean, proposalUnavailable: Boolean = false,
    makerStep: Int? = null, wishSubmitted: Boolean = false,
    campaignId: UUID? = null, pledgeId: UUID? = null,
): DemoGuide {
    val base = "/w/$token"
    val maker = path == "maker" || path.startsWith("maker/")
    val location = when {
        path == "maker" -> "Maker home"
        path == "maker/options" -> "Maker: options"
        path == "maker/wishes" -> "Maker: wishes"
        path == "maker/proposal" -> "Maker: proposal"
        path == "maker/offer" -> "Maker: offer"
        path == "maker/campaign" -> "Maker: campaign"
        path == "maker/result" -> "Maker: result"
        path == "maker/books" -> "Maker: books check"
        path == "maker/reliability-drills" -> "Maker: reliability drills"
        path == "maker/decision-record" -> "Maker: decision record"
        path == "maker/decision-record/verify" -> "Maker: record verification"
        path == "maker/questions" -> "Maker: questions to ask fans"
        path == "maker/replies" -> "Maker: reply drafts"
        path == "maker/glosses" -> "Maker: reading aids"
        path == "offer" -> "Fan: offer"
        path == "result" -> "Fan: result"
        path == "questions" -> "Fan: questions about your wish"
        path == "how-this-batch-was-chosen" ||
            (path.startsWith("campaigns/") && path.endsWith("/how-this-batch-was-chosen")) -> "Fan: batch explanation"
        path.startsWith("pledges/") && path.endsWith("/trace") -> "Fan: payment trail"
        path.startsWith("pledges/") -> "Fan: pledge status"
        path.isEmpty() -> "Fan: wishes"
        maker -> "Maker workspace"
        else -> "Workspace"
    }
    val next = if (path == "maker" && makerStep != null) {
        when (makerStep) {
            6 -> "View settlement result" to "$base/maker/result"
            5 -> "Open campaign dashboard" to "$base/maker/campaign"
            3 -> "Review proposal" to "$base/maker/proposal"
            else -> "Review options" to "$base/maker/options"
        }
    } else if (path == "maker/proposal") {
        if (proposalUnavailable) "Review the wishes before trying analysis again" to "$base/maker/wishes"
        else "Use the proposal below to confirm the offer terms" to "#main"
    } else if (path == "maker/offer") {
        "Complete the offer form and publish" to "#main"
    } else if (path == "maker/questions") {
        "Review and approve questions to ask fans" to "#main"
    } else if (path == "questions") {
        "Review the questions about your wish" to "#main"
    } else if (path.isEmpty() && wishSubmitted && state != CampaignState.OPEN) {
        "Switch to the maker view to review the wishes" to "$base/maker/wishes"
    } else if (state == CampaignState.FUNDED) {
        when {
            path == "maker/result" || path == "result" -> "Check against PayPal in the maker view" to
                ("$base/maker/books" + (campaignId?.let { "?campaignId=$it" } ?: ""))
            path == "maker/books" -> "Review the decision record" to "$base/maker/decision-record"
            path == "maker/decision-record" || path == "maker/reliability-drills" ->
                "Verify this record" to "$base/maker/decision-record/verify"
            path == "maker/decision-record/verify" -> if (pledgeId != null)
                "Review a pledge's payment trail" to "$base/pledges/$pledgeId/trace"
                else "No pledge trail is available; review the public batch explanation" to "$base/how-this-batch-was-chosen"
            (path.startsWith("pledges/") && path.endsWith("/trace")) || path == "how-this-batch-was-chosen" ||
                (path.startsWith("campaigns/") && path.endsWith("/how-this-batch-was-chosen")) ->
                "Done: the funded demo flow is complete. Review any payment attention before production." to null
            else -> if (maker) "Review the settlement result" to "$base/maker/result"
                else "Review the campaign result" to "$base/result"
        }
    } else if (maker) {
        when (state) {
            CampaignState.OPEN -> if (minimumMet) "Review the holds and confirm production on the campaign dashboard" to "$base/maker/campaign"
                else "Switch to the fan view and authorize a sandbox pledge" to "$base/offer"
            CampaignState.AWAITING_CONFIRMATION -> "Review and confirm production on the campaign dashboard" to "$base/maker/campaign"
            CampaignState.SETTLING -> "Check settlement on the campaign dashboard" to "$base/maker/campaign"
            CampaignState.FUNDED, CampaignState.NOT_FUNDED -> "Review the settlement result" to "$base/maker/result"
            else -> when (path) {
                "maker" -> "Review the maker's options" to "$base/maker/options"
                "maker/options" -> "Review the fans' wishes" to "$base/maker/wishes"
                "maker/wishes" -> "Review the proposal and evidence" to "$base/maker/proposal"
                else -> "Review the proposal and evidence" to "$base/maker/proposal"
            }
        }
    } else {
        when (state) {
            CampaignState.OPEN -> if (path.startsWith("pledges/")) "Switch to the maker view to check the campaign" to "$base/maker/campaign"
                else "Review the offer and authorize a sandbox pledge" to "$base/offer"
            CampaignState.AWAITING_CONFIRMATION, CampaignState.SETTLING -> "Check the campaign result" to "$base/result"
            CampaignState.FUNDED, CampaignState.NOT_FUNDED -> "Review the campaign result" to "$base/result"
            else -> "Leave a wish for the maker" to if (path.isEmpty()) "#main" else base
        }
    }
    return DemoGuide(location, next.first, next.second,
        if (maker) "Switch to fan view" else "Switch to maker view",
        if (maker) {
            when (state) {
                CampaignState.OPEN -> "$base/offer"
                CampaignState.AWAITING_CONFIRMATION, CampaignState.SETTLING,
                CampaignState.FUNDED, CampaignState.NOT_FUNDED -> "$base/result"
                else -> base
            }
        } else "$base/maker",
        if (state != null && state != CampaignState.DRAFT) "$base/how-this-batch-was-chosen" else null,
        if (maker) "$base/maker/decision-record" else null, maker)
}

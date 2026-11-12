package app.campaign.service

import app.campaign.model.AnalysisRun
import app.campaign.model.Campaign
import app.campaign.model.CampaignReason
import app.campaign.model.CampaignState
import app.campaign.model.Event
import app.campaign.model.Money
import app.campaign.model.Offer
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.port.AnalysisOutput
import app.campaign.port.PaymentResult
import app.campaign.port.PaymentStatus
import app.campaign.rules.CapacityDecision
import app.campaign.rules.PaymentAction
import app.campaign.rules.authorizedUnits
import app.campaign.rules.capturedUnits
import app.campaign.rules.checkCapacity
import app.campaign.rules.closesAt
import app.campaign.rules.confirmDeadline
import app.campaign.rules.funded
import app.campaign.rules.interestCount
import app.campaign.rules.requireCampaignTransition
import app.campaign.rules.thresholdMet
import app.campaign.rules.validateOffer
import app.campaign.store.AnalysisRunRepository
import app.campaign.store.CampaignRepository
import app.campaign.store.EventRepository
import app.campaign.store.OfferRepository
import app.campaign.store.OptionRepository
import app.campaign.store.PledgeRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.time.Clock
import java.time.Duration
import java.util.UUID

@Service
class CampaignService(
    private val persistence: CampaignPersistence,
    private val offers: OfferRepository,
    private val options: OptionRepository,
    private val analyses: AnalysisRunRepository,
    private val campaigns: CampaignRepository,
    private val pledges: PledgeRepository,
    private val events: EventRepository,
    private val payments: CampaignPayments,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    @Value("\${app.campaign.demo-simulation-enabled:false}") private val demoSimulationEnabled: Boolean,
) {
    fun createDraft(workspaceId: UUID, command: DraftOffer): ServiceResult<DraftCreated> = persistence.locked(workspaceId) {
        val option = persistence.checkpoint { options.findByIdAndWorkspaceId(command.optionId, workspaceId) }
            ?: return@locked rejected(Rejection.NOT_FOUND)
        val analysis = persistence.checkpoint { analyses.findByIdAndWorkspaceId(command.analysisRunId, workspaceId) }
            ?: return@locked rejected(Rejection.NOT_FOUND)
        val output = analysisOutput(analysis) ?: return@locked rejected(Rejection.INVALID_EVIDENCE)
        if (output.proposal?.optionId != option.id) return@locked rejected(Rejection.NO_PROPOSAL)
        if (command.supportingWishIds.any { id -> output.wishes.none { it.wishId == id } } ||
            command.chosenVariants.any { (key, value) -> value !in option.allowedVariants.getOrDefault(key, emptyList()) }
        ) return@locked rejected(Rejection.INVALID_EVIDENCE)
        val offer = Offer(workspaceId, option.id, analysis.id, command.title, command.specText,
            command.chosenVariants.toMap(), command.unitPrice, option.currency, command.deliveryTerms,
            option.shipRegion, command.minBatch, command.capacity, command.maxQtyPerPledge, command.supportingWishIds.toList())
        val validation = validateOffer(offer, option, command.duration)
        if (!validation.valid || command.title.isBlank() || command.specText.isBlank() || command.deliveryTerms.isBlank()) {
            return@locked ServiceResult.Rejected(Rejection.INVALID_OFFER, validation = validation)
        }
        // Bound the entire batch as well as each pledge so every displayed monetary total stays exact.
        try { Math.multiplyExact(offer.unitPrice, offer.capacity.toLong()) } catch (_: ArithmeticException) {
            return@locked rejected(Rejection.AMOUNT_OVERFLOW)
        }
        val campaign = Campaign(offer.id)
        persistence.checkpoint {
            offers.saveAndFlush(offer)
            campaigns.saveAndFlush(campaign)
            events.save(Event(campaign.id, "CAMPAIGN_CREATED", detail = "DRAFT", createdAt = clock.instant()))
        }
        ServiceResult.Success(DraftCreated(campaign.id, offer.id, validation.warnings))
    }

    fun publish(ref: CampaignRef, duration: Duration): ServiceResult<CampaignView> = withCampaign(ref) { context ->
        if (context.campaign.state != CampaignState.DRAFT) return@withCampaign rejected(Rejection.INVALID_STATE)
        val option = persistence.checkpoint { options.findByIdAndWorkspaceId(context.offer.optionId, ref.workspaceId) }
            ?: return@withCampaign rejected(Rejection.NOT_FOUND)
        val validation = validateOffer(context.offer, option, duration)
        if (!validation.valid) return@withCampaign ServiceResult.Rejected(Rejection.INVALID_OFFER, validation = validation)
        val existing = persistence.checkpoint {
            val offerIds = offers.findByWorkspaceId(ref.workspaceId).map { it.id }
            campaigns.findByOfferIdIn(offerIds).filter { it.id != ref.campaignId && it.state !in terminalStates }
        }
        if (existing.any { it.state != CampaignState.DRAFT }) return@withCampaign rejected(Rejection.ACTIVE_CAMPAIGN)
        context.campaign.openedAt = clock.instant()
        context.campaign.closesAt = closesAt(context.campaign.openedAt!!, duration)
        context.campaign.confirmDeadline = confirmDeadline(context.campaign.closesAt!!)
        move(context.campaign, CampaignState.OPEN)
        successView(context)
    }

    fun startPledge(ref: CampaignRef, quantity: Int): ServiceResult<PledgeView> = withCampaign(ref) { context ->
        reconcileLocked(context)
        if (context.campaign.state != CampaignState.OPEN) return@withCampaign rejected(Rejection.INVALID_STATE)
        capacityRejection(context, quantity)?.let { return@withCampaign rejected(it) }
        val amount = try { Math.multiplyExact(context.offer.unitPrice, quantity.toLong()) } catch (_: ArithmeticException) {
            return@withCampaign rejected(Rejection.AMOUNT_OVERFLOW)
        }
        val pledge = Pledge(context.campaign.id, quantity, amount, context.offer.currency, createdAt = clock.instant())
        persistence.checkpoint {
            pledges.saveAndFlush(pledge)
            events.save(Event(context.campaign.id, "PLEDGE_CREATED", pledge.id, detail = "CREATED", createdAt = clock.instant()))
        }
        val result = payments.createOrder(pledge)
        if (result is PaymentResult.Received && result.status in setOf(PaymentStatus.CREATED, PaymentStatus.APPROVED)) {
            pledge.paypalOrderId = result.resource.orderId.value
            payments.save(pledge)
            ServiceResult.Success(pledgeView(pledge))
        } else {
            pledge.lastError = "ORDER_UNCONFIRMED"
            payments.save(pledge)
            ServiceResult.Rejected(Rejection.PAYMENT_UNAVAILABLE, pledgeView(pledge))
        }
    }

    fun completePledge(ref: PledgeRef): ServiceResult<PledgeView> = withCampaign(ref.campaign) { context ->
        val pledge = context.pledges.find { it.id == ref.pledgeId } ?: return@withCampaign rejected(Rejection.NOT_FOUND)
        // Keep the in-flight callback out of deadline cleanup until its provider result is inspected.
        applyDeadlines(context)
        if (pledge.state != PledgeState.CREATED) {
            reconcileLocked(context)
            return@withCampaign if (pledge.state in setOf(PledgeState.AUTHORIZED, PledgeState.CAPTURED)) {
                ServiceResult.Success(pledgeView(pledge))
            } else ServiceResult.Rejected(Rejection.INVALID_STATE, pledgeView(pledge))
        }
        val outcome = authorizeAndCheck(context, pledge)
        if (context.campaign.state in terminalStates) compensate(context, refreshPending = false)
        updateAttention(context)
        outcome
    }

    fun withdrawPledge(ref: PledgeRef): ServiceResult<PledgeView> = withCampaign(ref.campaign) { context ->
        val pledge = context.pledges.find { it.id == ref.pledgeId } ?: return@withCampaign rejected(Rejection.NOT_FOUND)
        if (context.campaign.state != CampaignState.OPEN ||
            context.campaign.closesAt?.isAfter(clock.instant()) != true) {
            return@withCampaign ServiceResult.Rejected(Rejection.INVALID_STATE, pledgeView(pledge))
        }
        if (pledge.state == PledgeState.VOIDED) return@withCampaign ServiceResult.Success(pledgeView(pledge))
        if (pledge.state != PledgeState.AUTHORIZED) {
            return@withCampaign ServiceResult.Rejected(Rejection.INVALID_STATE, pledgeView(pledge))
        }
        payments.void(pledge)
        updateAttention(context)
        if (pledge.state == PledgeState.VOIDED) ServiceResult.Success(pledgeView(pledge))
        else ServiceResult.Rejected(Rejection.PAYMENT_UNAVAILABLE, pledgeView(pledge))
    }

    fun fundedBatch(ref: CampaignRef): ServiceResult<FundedBatch> = withCampaign(ref) { context ->
        if (context.campaign.state != CampaignState.FUNDED) return@withCampaign rejected(Rejection.INVALID_STATE)
        val (paid, incomplete) = context.pledges.partition { it.state == PledgeState.CAPTURED }
        fun row(pledge: Pledge) = BatchPledge(pledge.id, pledge.shippingAddress?.fullName, pledge.qty,
            pledge.shippingAddress, pledge.state, pledge.payerEmailMasked)
        ServiceResult.Success(FundedBatch(paid.map(::row), incomplete.map(::row),
            paid.sumOf { it.qty.toLong() }, paid.fold(BigInteger.ZERO) { total, pledge -> total + BigInteger.valueOf(pledge.amount) },
            context.offer.currency))
    }

    fun cancel(ref: CampaignRef): ServiceResult<CampaignView> = withCampaign(ref) { context ->
        reconcileLocked(context)
        if (context.campaign.state !in setOf(CampaignState.OPEN, CampaignState.AWAITING_CONFIRMATION)) {
            return@withCampaign rejected(Rejection.INVALID_STATE)
        }
        move(context.campaign, CampaignState.NOT_FUNDED, CampaignReason.CANCELLED_BY_MAKER)
        compensate(context, refreshPending = false)
        successView(context)
    }

    fun confirmProduction(ref: CampaignRef): ServiceResult<CampaignView> = withCampaign(ref) { context ->
        if (context.campaign.state == CampaignState.SETTLING || context.campaign.state in terminalStates) {
            reconcileLocked(context)
            return@withCampaign successView(context)
        }
        reconcileLocked(context)
        if (context.campaign.state !in setOf(CampaignState.OPEN, CampaignState.AWAITING_CONFIRMATION)) {
            return@withCampaign rejected(Rejection.INVALID_STATE)
        }
        if (!thresholdMet(authorizedUnits(context.pledges), context.offer.minBatch)) {
            return@withCampaign rejected(Rejection.THRESHOLD_NOT_MET)
        }
        move(context.campaign, CampaignState.SETTLING)
        settle(context)
        successView(context)
    }

    fun reconcile(ref: CampaignRef): ServiceResult<CampaignView> = withCampaign(ref) { context ->
        refreshProviderTruth(context)
        reconcileLocked(context)
        successView(context)
    }

    fun readCampaign(ref: CampaignRef): ServiceResult<CampaignView> = reconcile(ref)

    fun simulateNextCaptureFailure(ref: CampaignRef): ServiceResult<CampaignView> = withCampaign(ref) { context ->
        if (!demoSimulationEnabled) return@withCampaign rejected(Rejection.SIMULATION_DISABLED)
        if (context.campaign.state !in setOf(CampaignState.OPEN, CampaignState.AWAITING_CONFIRMATION)) {
            return@withCampaign rejected(Rejection.INVALID_STATE)
        }
        persistence.checkpoint {
            if (events.findByCampaignIdOrderByCreatedAtAscIdAsc(ref.campaignId).count { it.type == "SIMULATION_ARMED" } ==
                events.findByCampaignIdOrderByCreatedAtAscIdAsc(ref.campaignId).count { it.type == "SIMULATION_CONSUMED" }) {
                events.save(Event(ref.campaignId, "SIMULATION_ARMED", detail = "Next capture failure is a demo simulation.", createdAt = clock.instant()))
            }
        }
        successView(context)
    }

    private fun authorizeAndCheck(context: Context, pledge: Pledge): ServiceResult<PledgeView> {
        if (pledge.paypalOrderId == null) return ServiceResult.Rejected(Rejection.PAYMENT_UNAVAILABLE, pledgeView(pledge))
        val result = if (pledge.authorizationId == null) payments.authorize(pledge) else payments.readAuthorization(pledge)
        if (result !is PaymentResult.Received) {
            pledge.lastError = "AUTHORIZATION_UNCONFIRMED"
            if (result.status in setOf(PaymentStatus.DECLINED, PaymentStatus.FAILED) && pledge.authorizationId == null &&
                !payments.transportFailureObserved(pledge, PaymentAction.AUTHORIZE)) {
                payments.move(pledge, PledgeState.ABANDONED)
            } else payments.save(pledge)
            return ServiceResult.Rejected(Rejection.PAYMENT_REJECTED, pledgeView(pledge))
        }
        val authorization = result.resource
        if (pledge.authorizationId != null && authorization.authorizationId.value != pledge.authorizationId) {
            pledge.lastError = "AUTHORIZATION_EVIDENCE_MISMATCH"
            payments.move(pledge, PledgeState.VOID_FAILED)
            return ServiceResult.Rejected(Rejection.PAYMENT_MISMATCH, pledgeView(pledge))
        }
        pledge.authorizationId = authorization.authorizationId.value
        pledge.authorizationExpiresAt = authorization.expiresAt
        pledge.payerEmailMasked = authorization.payerEmail?.let(::maskEmail)
        pledge.shippingAddress = authorization.shippingAddress
        payments.save(pledge)
        applyDeadlines(context)
        val rejection = when {
            authorization.amount != Money(pledge.amount, pledge.currency) -> Rejection.PAYMENT_MISMATCH
            authorization.orderId != null && authorization.orderId.value != pledge.paypalOrderId -> Rejection.PAYMENT_MISMATCH
            result.status != PaymentStatus.CREATED -> Rejection.PAYMENT_REJECTED
            authorization.expiresAt?.let { !clock.instant().isBefore(it) } == true -> Rejection.PAYMENT_REJECTED
            context.campaign.state != CampaignState.OPEN -> Rejection.INVALID_STATE
            else -> capacityRejection(context, pledge.qty)
        }
        return if (rejection != null) {
            pledge.lastError = rejection.name
            payments.void(pledge)
            ServiceResult.Rejected(rejection, pledgeView(pledge))
        } else {
            pledge.lastError = null
            payments.move(pledge, PledgeState.AUTHORIZED)
            ServiceResult.Success(pledgeView(pledge))
        }
    }

    private fun reconcileLocked(context: Context) {
        applyDeadlines(context)
        context.pledges.filter { it.state == PledgeState.CREATED && payments.attempted(it, PaymentAction.AUTHORIZE) &&
            payments.recheckDue(it, if (it.authorizationId == null) PaymentAction.AUTHORIZE else PaymentAction.READ_AUTHORIZATION) }
            .forEach { authorizeAndCheck(context, it) }
        applyDeadlines(context)
        when {
            context.campaign.state == CampaignState.SETTLING -> settle(context)
            context.campaign.state in terminalStates -> compensate(context, refreshPending = true)
        }
        if (context.campaign.state in setOf(CampaignState.OPEN, CampaignState.AWAITING_CONFIRMATION)) {
            context.pledges.filter { it.state == PledgeState.VOID_FAILED && payments.recheckDue(it, PaymentAction.VOID) }
                .forEach(payments::void)
        }
        updateAttention(context)
    }

    private fun refreshProviderTruth(context: Context) {
        when (context.campaign.state) {
            CampaignState.OPEN, CampaignState.AWAITING_CONFIRMATION -> context.pledges
                .filter { it.state in setOf(PledgeState.AUTHORIZED, PledgeState.CAPTURE_PENDING) }
                .forEach {
                    if (payments.recheckDue(it, PaymentAction.READ_AUTHORIZATION, PaymentAction.READ_CAPTURE)) {
                        if (it.state == PledgeState.AUTHORIZED) payments.refreshAuthorization(it)
                        else payments.refreshCapture(it)
                    }
                }
            CampaignState.FUNDED -> context.pledges
                .filter { it.state == PledgeState.CAPTURED && payments.recheckDue(it, PaymentAction.READ_CAPTURE) }
                .forEach(payments::refreshPaidCapture)
            else -> Unit
        }
    }

    private fun applyDeadlines(context: Context) {
        val campaign = context.campaign
        val now = clock.instant()
        if (campaign.state == CampaignState.OPEN && !now.isBefore(requireNotNull(campaign.closesAt))) {
            if (thresholdMet(authorizedUnits(context.pledges), context.offer.minBatch)) {
                move(campaign, CampaignState.AWAITING_CONFIRMATION)
            } else move(campaign, CampaignState.NOT_FUNDED, CampaignReason.THRESHOLD_NOT_MET)
        }
        if (campaign.state == CampaignState.AWAITING_CONFIRMATION && now.isAfter(requireNotNull(campaign.confirmDeadline))) {
            move(campaign, CampaignState.NOT_FUNDED, CampaignReason.CONFIRMATION_EXPIRED)
        }
    }

    private fun settle(context: Context) {
        context.pledges.filter { it.state == PledgeState.CAPTURE_PENDING &&
            payments.recheckDue(it, PaymentAction.READ_CAPTURE, PaymentAction.READ_AUTHORIZATION) }.forEach {
            payments.refreshCapture(it, allowNewCapture = !clock.instant().isAfter(context.campaign.confirmDeadline))
        }
        for (pledge in context.pledges.filter { it.state == PledgeState.AUTHORIZED }) {
            if (clock.instant().isAfter(context.campaign.confirmDeadline) && !payments.attempted(pledge, PaymentAction.CAPTURE)) {
                payments.void(pledge)
            } else payments.capture(pledge, consumeSimulation(context, pledge),
                allowNewCapture = !clock.instant().isAfter(context.campaign.confirmDeadline))
        }
        if (funded(capturedUnits(context.pledges), context.offer.minBatch)) {
            move(context.campaign, CampaignState.FUNDED)
        } else move(context.campaign, CampaignState.NOT_FUNDED, CampaignReason.CAPTURE_SHORTFALL)
        compensate(context, refreshPending = false)
    }

    private fun compensate(context: Context, refreshPending: Boolean) {
        for (pledge in context.pledges) {
            if (refreshPending && pledge.state == PledgeState.CAPTURE_PENDING &&
                payments.recheckDue(pledge, PaymentAction.READ_CAPTURE, PaymentAction.READ_AUTHORIZATION)) payments.refreshCapture(pledge)
            if (pledge.state in setOf(PledgeState.AUTHORIZED, PledgeState.CAPTURE_FAILED, PledgeState.VOID_FAILED) ||
                (pledge.state == PledgeState.CREATED && pledge.authorizationId != null)) {
                if (payments.recheckDue(pledge, PaymentAction.VOID)) payments.void(pledge)
            }
            if (pledge.state == PledgeState.CAPTURED && context.campaign.state == CampaignState.NOT_FUNDED &&
                payments.recheckDue(pledge, PaymentAction.REFUND)) {
                payments.refund(pledge)
            } else if (refreshPending && pledge.state in setOf(PledgeState.REFUND_PENDING, PledgeState.REFUND_FAILED) &&
                payments.recheckDue(pledge, PaymentAction.REFUND, PaymentAction.READ_REFUND)) {
                payments.refund(pledge)
            }
            if (pledge.state == PledgeState.CREATED && !payments.attempted(pledge, PaymentAction.AUTHORIZE)) {
                payments.move(pledge, PledgeState.ABANDONED)
            }
        }
        updateAttention(context)
    }

    private fun consumeSimulation(context: Context, pledge: Pledge): Boolean {
        if (!demoSimulationEnabled || payments.attempted(pledge, PaymentAction.CAPTURE)) return false
        return persistence.checkpoint {
            val timeline = events.findByCampaignIdOrderByCreatedAtAscIdAsc(context.campaign.id)
            if (timeline.count { it.type == "SIMULATION_ARMED" } > timeline.count { it.type == "SIMULATION_CONSUMED" }) {
                events.save(Event(context.campaign.id, "SIMULATION_CONSUMED", pledge.id, createdAt = clock.instant()))
                true
            } else false
        }
    }

    private fun capacityRejection(context: Context, quantity: Int): Rejection? = when (
        checkCapacity(context.offer.capacity, authorizedUnits(context.pledges), quantity, context.offer.maxQtyPerPledge)
    ) {
        CapacityDecision.ALLOWED -> null
        CapacityDecision.INVALID_QUANTITY -> Rejection.INVALID_QUANTITY
        CapacityDecision.CAPACITY_EXCEEDED -> Rejection.CAPACITY_EXCEEDED
    }

    private fun move(campaign: Campaign, state: CampaignState, reason: CampaignReason? = null) {
        val previous = campaign.state
        campaign.state = requireCampaignTransition(previous, state, reason)
        campaign.reason = reason
        persistence.checkpoint {
            campaigns.saveAndFlush(campaign)
            events.save(Event(campaign.id, "CAMPAIGN_STATE", detail = "$previous -> $state${reason?.let { ": $it" } ?: ""}", createdAt = clock.instant()))
        }
    }

    private fun updateAttention(context: Context) {
        context.campaign.needsAttention = context.pledges.any(::needsAttention) || fundedShortfall(context)
        persistence.checkpoint { campaigns.saveAndFlush(context.campaign) }
    }

    private fun <T : Any> withCampaign(ref: CampaignRef, action: (Context) -> ServiceResult<T>): ServiceResult<T> =
        persistence.locked(ref.workspaceId) {
            val context = persistence.checkpoint {
                val campaign = campaigns.findById(ref.campaignId).orElse(null) ?: return@checkpoint null
                val offer = offers.findByIdAndWorkspaceId(campaign.offerId, ref.workspaceId) ?: return@checkpoint null
                Context(campaign, offer, pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id))
            } ?: return@locked rejected(Rejection.NOT_FOUND)
            action(context)
        }

    private fun successView(context: Context): ServiceResult<CampaignView> = ServiceResult.Success(view(context))

    private fun view(context: Context): CampaignView {
        val campaign = context.campaign
        val offer = context.offer
        val authorized = authorizedUnits(context.pledges)
        val captured = capturedUnits(context.pledges)
        val shortfall = campaign.state == CampaignState.FUNDED && !funded(captured, offer.minBatch)
        val analysis = persistence.checkpoint { analyses.findByIdAndWorkspaceId(offer.analysisRunId, offer.workspaceId) }
        val interest = analysis?.let(::analysisOutput)?.let { interestCount(it.wishes) } ?: 0
        val timeline = persistence.checkpoint { events.findByCampaignIdOrderByCreatedAtAscIdAsc(campaign.id) }
        val deadline = when (campaign.state) {
            CampaignState.OPEN -> campaign.closesAt
            CampaignState.AWAITING_CONFIRMATION -> campaign.confirmDeadline
            else -> null
        }
        val timeLeft = deadline?.let { Duration.between(clock.instant(), it).coerceAtLeast(Duration.ZERO) } ?: Duration.ZERO
        val holds = context.pledges.filter { it.state == PledgeState.AUTHORIZED }
        return CampaignView(campaign.id,
            OfferView(offer.id, offer.optionId, offer.analysisRunId, offer.title, offer.specText, offer.chosenVariants.toMap(),
                offer.unitPrice, offer.currency, offer.deliveryTerms, offer.shipRegion, offer.minBatch, offer.capacity,
                offer.maxQtyPerPledge, offer.supportingWishIds.toList()),
            campaign.state, campaign.reason, campaign.openedAt, campaign.closesAt, campaign.confirmDeadline,
            CampaignCounts(interest, authorized, captured),
            MinimumProgress(if (campaign.state in terminalStates || campaign.state == CampaignState.SETTLING) captured else authorized, offer.minBatch),
            (offer.capacity.toLong() - authorized - captured).coerceAtLeast(0), timeLeft,
            ConfirmationSummary(holds.size, authorized, holds.fold(0L) { sum, pledge -> Math.addExact(sum, pledge.amount) }, offer.currency),
            context.pledges.map(::pledgeView),
            ResultSummary(campaign.state, campaign.reason, campaign.state == CampaignState.FUNDED, captured,
                context.pledges.filter { it.state == PledgeState.REFUNDED }.sumOf { it.qty.toLong() },
                context.pledges.filter { it.state == PledgeState.VOIDED }.sumOf { it.qty.toLong() }),
            context.pledges.filter { needsAttention(it) || shortfall && it.state == PledgeState.REFUNDED }
                .map { AttentionItem(it.id, it.state, if (shortfall && it.state == PledgeState.REFUNDED)
                    "FUNDED_PAYMENT_SHORTFALL" else it.lastError) },
            timeline.map { TimelineEntry(it.type, it.pledgeId, it.paypalEndpoint, it.paypalId, it.paypalStatus, it.debugId, it.detail, it.createdAt) })
    }

    private fun analysisOutput(run: AnalysisRun): AnalysisOutput? = try {
        mapper.readValue(run.resultJson, AnalysisOutput::class.java)
    } catch (_: tools.jackson.core.JacksonException) { null }

    private fun pledgeView(pledge: Pledge) = PledgeView(pledge.id, pledge.qty, pledge.amount, pledge.currency, pledge.state,
        pledge.paypalOrderId, pledge.authorizationId, pledge.captureId, pledge.refundId, pledge.authorizationExpiresAt,
        pledge.payerEmailMasked, pledge.lastError)

    private fun maskEmail(email: String): String = email.substringBefore('@').take(1) + "***@" +
        email.substringAfter('@', "***").ifBlank { "***" }

    private fun needsAttention(pledge: Pledge): Boolean = pledge.state in attentionStates ||
        (pledge.state == PledgeState.CREATED && pledge.lastError == "AUTHORIZATION_UNCONFIRMED") ||
        (pledge.state in setOf(PledgeState.AUTHORIZED, PledgeState.CAPTURED) && pledge.lastError != null)

    private fun fundedShortfall(context: Context): Boolean = context.campaign.state == CampaignState.FUNDED &&
        !funded(capturedUnits(context.pledges), context.offer.minBatch)

    private fun rejected(reason: Rejection) = ServiceResult.Rejected(reason)
    private data class Context(val campaign: Campaign, val offer: Offer, val pledges: List<Pledge>)
    private val terminalStates = setOf(CampaignState.FUNDED, CampaignState.NOT_FUNDED)
    private val attentionStates = setOf(PledgeState.CAPTURE_PENDING, PledgeState.REFUND_PENDING, PledgeState.VOID_FAILED, PledgeState.REFUND_FAILED)
}

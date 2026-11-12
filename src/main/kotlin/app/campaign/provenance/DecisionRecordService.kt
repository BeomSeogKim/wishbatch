package app.campaign.provenance

import app.campaign.followup.FollowupAnswerRecord
import app.campaign.followup.FollowupQuestion
import app.campaign.model.AnalysisRun
import app.campaign.model.Campaign
import app.campaign.model.Offer
import app.campaign.model.Pledge
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.port.AnalysisOutput
import app.campaign.review.WishReviewOverride
import app.campaign.terms.SealedOfferChanged
import app.campaign.terms.SealedOfferTerms
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Lifecycle writers join the caller's transaction; reads never create a baseline.
 * Call after saving the source in the same checkpoint, before it can be edited.
 * Existing snapshots cannot be replaced. Missing earlier hooks remain unavailable.
 */
@Service
@Transactional
class DecisionRecordService(
    private val entities: EntityManager,
    private val mapper: JsonMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val hashing = DecisionRecordHash(mapper)

    /** Supply the exact analyzed input, including for stored seed runs, at run persistence. */
    fun recordAnalysis(run: AnalysisRun, wishes: List<Wish>) {
        entities.flush()
        val saved = requireNotNull(entities.find(AnalysisRun::class.java, run.id, LockModeType.PESSIMISTIC_WRITE))
        require(saved.workspaceId == run.workspaceId)
        require(wishes.all { it.workspaceId == run.workspaceId })
        require(wishes.map { it.id }.distinct().size == wishes.size)
        val output = mapper.readValue(run.resultJson, AnalysisOutput::class.java)
        require(output.wishes.map { it.wishId }.toSet() == wishes.map { it.id }.toSet())
        val requested = analysisPayload(run, wishes, persistedTimestamp(run.createdAt))
        // Flush leaves managed timestamps at caller precision; snapshots need the database value.
        entities.refresh(saved)
        val payload = analysisPayload(saved, wishes)
        require(payload == requested)
        storeSource(saved.workspaceId, "ANALYSIS", saved.id, payload)
    }

    fun recordWishReview(
        review: WishReviewOverride,
        answers: List<FollowupAnswerRecord>,
        questions: List<FollowupQuestion>,
    ) {
        entities.flush()
        val saved = requireNotNull(entities.find(WishReviewOverride::class.java, review.id))
        require(reviewSnapshot(saved) == reviewSnapshot(review))
        val run = requireNotNull(entities.find(AnalysisRun::class.java, saved.runId))
        val wish = requireNotNull(entities.find(Wish::class.java, saved.wishId))
        require(run.workspaceId == saved.workspaceId && wish.workspaceId == saved.workspaceId)
        require(answers.map { it.id }.distinct().size == answers.size)
        require(questions.map { it.id }.distinct().size == questions.size)
        val questionSnapshots = questions.sortedBy { it.id.toString() }.map { question ->
            val persisted = requireNotNull(entities.find(FollowupQuestion::class.java, question.id))
            require(persisted.workspaceId == saved.workspaceId && persisted.runId == saved.runId &&
                persisted.optionId == saved.optionId && persisted.approved && saved.wishId in persisted.wishIds)
            mapOf("id" to persisted.id, "text" to persisted.text, "attribute" to persisted.attribute,
                "variantKey" to persisted.variantKey, "choices" to persisted.choices,
                "wishIds" to persisted.wishIds.sortedBy { it.toString() }, "approved" to true,
                "createdAt" to persistedTimestamp(persisted.createdAt).toString())
        }
        val answerSnapshots = answers.sortedBy { it.id.toString() }.map { answer ->
            val persisted = requireNotNull(entities.find(FollowupAnswerRecord::class.java, answer.id))
            require(persisted.workspaceId == saved.workspaceId && persisted.runId == saved.runId &&
                persisted.optionId == saved.optionId && persisted.wishId == saved.wishId)
            require(questions.any { it.id == persisted.questionId })
            val question = requireNotNull(entities.find(FollowupQuestion::class.java, persisted.questionId))
            require(persisted.choice in question.choices)
            mapOf("id" to persisted.id, "questionId" to persisted.questionId, "wishId" to persisted.wishId,
                "choice" to persisted.choice, "createdAt" to persistedTimestamp(persisted.createdAt).toString())
        }
        require(saved.source != "FAN_ANSWER" || answers.any { it.id == saved.answerId })
        storeSource(saved.workspaceId, "REVIEW", saved.id, hashing.canonical(mapOf(
            "workspaceId" to saved.workspaceId, "runId" to saved.runId, "wishId" to saved.wishId,
            "optionId" to saved.optionId, "review" to reviewSnapshot(saved),
            "questions" to questionSnapshots, "answers" to answerSnapshots,
        )))
    }

    /** Call at publication. The offer ID is its version; the stored source hash is its terms hash. */
    fun recordOffer(offer: Offer) {
        entities.flush()
        val saved = requireNotNull(entities.find(Offer::class.java, offer.id, LockModeType.PESSIMISTIC_WRITE))
        require(saved.workspaceId == offer.workspaceId && offerPayload(saved) == offerPayload(offer))
        storeSource(offer.workspaceId, "OFFER", offer.id, offerPayload(offer))
    }

    /** Missing snapshots return null; changed seals have no usable terms and never get repaired. */
    @Transactional(readOnly = true, noRollbackFor = [SealedOfferChanged::class])
    fun sealedOffer(workspaceId: UUID, offerId: UUID): SealedOfferTerms? {
        val offer = entities.find(Offer::class.java, offerId) ?: return null
        if (offer.workspaceId != workspaceId) return null
        val snapshot = entities.createQuery(
            "select s from DecisionSourceSnapshot s where s.sourceType = 'OFFER' and s.sourceId = :id",
            DecisionSourceSnapshot::class.java,
        ).setParameter("id", offerId).resultList.singleOrNull() ?: return null
        if (snapshot.workspaceId != workspaceId || snapshot.sourceId != offer.id ||
            hashing.sha256(snapshot.payload) != snapshot.hash || snapshot.payload != offerPayload(offer)) {
            throw SealedOfferChanged()
        }
        return SealedOfferTerms(offer.id, snapshot.hash, offer.unitPrice, offer.currency)
    }

    /**
     * Call at pledge creation and every persisted payment checkpoint. No wish association
     * is inferred from offer evidence. Repeated unchanged checkpoints append nothing.
     * Source drift is reported on reads without blocking payment evidence. Identity or
     * chain corruption still throws and rolls back the caller's joined transaction.
     */
    fun recordPledge(pledge: Pledge) {
        entities.flush()
        val saved = requireNotNull(entities.find(Pledge::class.java, pledge.id, LockModeType.PESSIMISTIC_WRITE))
        require(paymentPayload(saved) == paymentPayload(pledge, persistedTimestamp(pledge.createdAt),
            pledge.authorizationExpiresAt?.let(::persistedTimestamp))) {
            "Persisted payment checkpoint differs from supplied pledge"
        }
        val campaign = requireNotNull(entities.find(Campaign::class.java, pledge.campaignId))
        val offer = requireNotNull(entities.find(Offer::class.java, campaign.offerId))
        var record = entities.find(PledgeDecisionRecord::class.java, pledge.id)
        if (record == null) {
            record = PledgeDecisionRecord(pledge.id, offer.workspaceId, campaign.id, offer.id)
            entities.persist(record)
            val analysis = source("ANALYSIS", offer.analysisRunId, offer.workspaceId)
            val offerSnapshot = source("OFFER", offer.id, offer.workspaceId)
            val wish = saved.wishId?.let { id ->
                requireNotNull(entities.find(Wish::class.java, id))
                    .also { require(it.workspaceId == offer.workspaceId) }
            }
            append(record, "WISH", hashing.canonical(mapOf(
                "availability" to if (wish == null) "UNAVAILABLE" else "AVAILABLE",
                "traceScope" to if (wish == null) "OFFER_EVIDENCE" else "WISH",
                "reason" to if (wish == null) "No wish was linked. This pledge traces to the offer's evidence set." else null,
                "wishId" to wish?.id, "text" to wish?.text, "textHash" to wish?.text?.let(hashing::sha256),
                "supportingWishIds" to offer.supportingWishIds.sortedBy { it.toString() },
            )))
            append(record, "VERDICT", verdictPayload(offer, wish, analysis))
            append(record, "OFFER", hashing.canonical(mapOf(
                "availability" to if (offerSnapshot == null) "UNAVAILABLE" else "AVAILABLE",
                "offerId" to offer.id, "termsHash" to offerSnapshot?.hash,
                "terms" to offerSnapshot?.let { mapper.readTree(it.payload) },
            )))
        }
        check(record.workspaceId == offer.workspaceId && record.campaignId == campaign.id && record.offerId == offer.id) {
            "Stored decision record identity changed"
        }
        val entries = entries(pledge.id)
        check(chainValid(record, entries)) { "Stored decision record failed integrity verification" }
        val payment = paymentPayload(saved)
        if (!paymentMatches(entries.lastOrNull { it.kind == "PAYMENT" }?.payload, payment)) {
            val payload = mapper.readTree(payment).deepCopy() as tools.jackson.databind.node.ObjectNode
            payload.put("recordedAt", clock.instant().toString())
            append(record, "PAYMENT", hashing.canonical(payload))
        }
    }

    internal fun source(type: String, id: UUID, workspaceId: UUID): DecisionSourceSnapshot? = entities.createQuery(
        "select s from DecisionSourceSnapshot s where s.sourceType = :type and s.sourceId = :id and s.workspaceId = :workspace",
        DecisionSourceSnapshot::class.java,
    ).setParameter("type", type).setParameter("id", id).setParameter("workspace", workspaceId)
        .resultList.singleOrNull()

    internal fun entries(pledgeId: UUID): List<DecisionRecordEntry> = entities.createQuery(
        "select e from DecisionRecordEntry e where e.pledgeId = :id order by e.position", DecisionRecordEntry::class.java,
    ).setParameter("id", pledgeId).resultList

    internal fun chainValid(record: PledgeDecisionRecord, entries: List<DecisionRecordEntry>): Boolean =
        DecisionRecordVerification(mapper).verify(record, entries).intact

    internal fun paymentMatches(recorded: String?, current: String): Boolean = recorded != null && runCatching {
        val payload = mapper.readTree(recorded).deepCopy() as tools.jackson.databind.node.ObjectNode
        payload.remove("recordedAt")
        hashing.canonical(payload) == current
    }.getOrDefault(false)

    private fun verdictPayload(offer: Offer, wish: Wish?, analysis: DecisionSourceSnapshot?): String {
        val run = entities.find(AnalysisRun::class.java, offer.analysisRunId)
        val output = run?.takeIf { it.workspaceId == offer.workspaceId }
            ?.let { mapper.readValue(it.resultJson, AnalysisOutput::class.java) }
        val reading = output?.wishes?.singleOrNull { it.wishId == wish?.id }
        val original = output?.verdicts?.singleOrNull { it.wishId == wish?.id && it.optionId == offer.optionId }
        val review = wish?.let {
            entities.createQuery(
                "select r from WishReviewOverride r where r.workspaceId = :workspace and r.runId = :run " +
                    "and r.wishId = :wish and r.optionId = :option order by r.revision desc", WishReviewOverride::class.java,
            ).setParameter("workspace", offer.workspaceId).setParameter("run", offer.analysisRunId)
                .setParameter("wish", it.id).setParameter("option", offer.optionId).setMaxResults(1).resultList.singleOrNull()
        }
        return hashing.canonical(mapOf(
            "availability" to if (wish != null && (reading != null || review != null)) "AVAILABLE" else "UNAVAILABLE",
            "analysisRunId" to offer.analysisRunId, "analysisSnapshotHash" to analysis?.hash,
            "model" to analysis?.let { mapper.readTree(it.payload).get("model").asString() },
            "inputHash" to analysis?.let { mapper.readTree(it.payload).get("inputHash").asString() },
            "optionId" to offer.optionId, "wishId" to wish?.id,
            "kind" to if (review != null) WishKind.REQUEST else reading?.kind,
            "originalVerdict" to original?.verdict, "originalReason" to original?.reason, "verdict" to (review?.verdict ?: original?.verdict),
            "reason" to (review?.reason ?: original?.reason ?: if (wish == null)
                "No wish was linked. This pledge traces to the offer's evidence set."
                else if (reading?.kind == WishKind.NOT_A_REQUEST) "This was read as not a request."
                else "This wish has no recorded reading for the offered option."),
            "readingAgreement" to output?.secondReading?.comparisons?.singleOrNull {
                it.wishId == wish?.id && it.optionId == offer.optionId
            },
            "makerOverride" to review?.takeIf { it.source == "MAKER" }?.let(::reviewSnapshot),
            "review" to review?.let(::reviewSnapshot),
            "reviewSnapshotHash" to review?.let { source("REVIEW", it.id, offer.workspaceId)?.hash },
            "reviewEvidence" to review?.let { source("REVIEW", it.id, offer.workspaceId) }?.let { mapper.readTree(it.payload) },
        ))
    }

    private fun reviewSnapshot(review: WishReviewOverride): Map<String, Any?> = mapOf(
        "id" to review.id, "revision" to review.revision, "source" to review.source,
        "verdict" to review.verdict, "previousVerdict" to review.previousVerdict,
        "reason" to review.reason, "createdAt" to persistedTimestamp(review.createdAt).toString(), "answerId" to review.answerId,
    )

    internal fun sourcesValid(record: PledgeDecisionRecord, entries: List<DecisionRecordEntry>): Boolean = runCatching {
        val wishEntry = mapper.readTree(entries.single { it.kind == "WISH" }.payload)
        val recordedWishId = wishEntry.get("wishId")?.takeUnless { it.isNull }?.asString()?.let(UUID::fromString)
        val pledge = entities.find(Pledge::class.java, record.pledgeId) ?: return false
        if (recordedWishId != pledge.wishId) return false
        if (recordedWishId != null) {
            val wish = entities.find(Wish::class.java, recordedWishId) ?: return false
            if (wish.workspaceId != record.workspaceId || wishEntry.get("text").asString() != wish.text ||
                wishEntry.get("textHash").asString() != hashing.sha256(wish.text)) return false
        }
        val offerEntry = mapper.readTree(entries.single { it.kind == "OFFER" }.payload)
        val verdictEntry = mapper.readTree(entries.single { it.kind == "VERDICT" }.payload)
        val reviewHash = verdictEntry.get("reviewSnapshotHash")
        if (reviewHash != null && !reviewHash.isNull) {
            val reviewId = UUID.fromString(verdictEntry.get("review").get("id").asString())
            val snapshot = source("REVIEW", reviewId, record.workspaceId)
            if (!referenceMatches(reviewHash, snapshot)) return false
            if (snapshot == null || mapper.readTree(snapshot.payload) != verdictEntry.get("reviewEvidence")) return false
            val review = entities.find(WishReviewOverride::class.java, reviewId) ?: return false
            if (hashing.canonical(reviewSnapshot(review)) != hashing.canonical(verdictEntry.get("review"))) return false
        }
        val campaign = entities.find(Campaign::class.java, record.campaignId) ?: return false
        if (campaign.offerId != record.offerId) return false
        val offer = entities.find(Offer::class.java, record.offerId) ?: return false
        if (offer.workspaceId != record.workspaceId) return false
        val offerSnapshot = source("OFFER", record.offerId, record.workspaceId)
        if (!referenceMatches(offerEntry.get("termsHash"), offerSnapshot)) return false
        if (offerSnapshot != null && offerSnapshot.payload != offerPayload(offer)) return false
        val runId = UUID.fromString(verdictEntry.get("analysisRunId").asString())
        if (runId != offer.analysisRunId) return false
        val analysis = source("ANALYSIS", runId, record.workspaceId)
        if (!referenceMatches(verdictEntry.get("analysisSnapshotHash"), analysis)) return false
        if (analysis != null) {
            val run = entities.find(AnalysisRun::class.java, runId) ?: return false
            if (run.workspaceId != record.workspaceId) return false
            val wishIds = mapper.readTree(analysis.payload).get("wishes").toList().map { UUID.fromString(it.get("wishId").asString()) }
            val wishes = wishIds.map { entities.find(Wish::class.java, it) ?: return false }
            if (wishes.any { it.workspaceId != record.workspaceId } || analysis.payload != analysisPayload(run, wishes)) return false
        }
        true
    }.getOrDefault(false)

    private fun referenceMatches(reference: tools.jackson.databind.JsonNode, snapshot: DecisionSourceSnapshot?): Boolean =
        if (reference.isNull) true
        else snapshot != null && reference.asString() == snapshot.hash && hashing.sha256(snapshot.payload) == snapshot.hash

    internal fun paymentPayload(
        pledge: Pledge,
        createdAt: Instant = pledge.createdAt,
        authorizationExpiresAt: Instant? = pledge.authorizationExpiresAt,
    ): String = hashing.canonical(mapOf(
        "pledgeId" to pledge.id, "campaignId" to pledge.campaignId, "createdAt" to createdAt.toString(),
        "quantity" to pledge.qty, "amountMinor" to pledge.amount, "currency" to pledge.currency,
        "orderId" to pledge.paypalOrderId, "authorizationId" to pledge.authorizationId,
        "authorizationExpiresAt" to authorizationExpiresAt?.toString(), "captureId" to pledge.captureId,
        "refundId" to pledge.refundId, "state" to pledge.state.name,
        "simulatedCaptureFailure" to (pledge.lastError == "SIMULATED_CAPTURE_FAILURE"),
    ))

    // Both supported stores round timestamp(6) values to the nearest microsecond.
    private fun persistedTimestamp(value: Instant): Instant = value.plusNanos(500).truncatedTo(ChronoUnit.MICROS)

    private fun analysisPayload(run: AnalysisRun, wishes: List<Wish>, createdAt: Instant = run.createdAt): String {
        val output = mapper.readValue(run.resultJson, AnalysisOutput::class.java)
        return hashing.canonical(mapOf(
            "version" to 1, "workspaceId" to run.workspaceId, "runId" to run.id, "model" to run.model,
            "inputHash" to run.inputHash, "createdAt" to createdAt.toString(), "seed" to run.isSeed,
            "resultHash" to hashing.sha256(run.resultJson),
            "wishes" to wishes.sortedBy { it.id.toString() }.map { mapOf("wishId" to it.id, "text" to it.text) },
            "wishKinds" to output.wishes.sortedBy { it.wishId.toString() }.map { mapOf("wishId" to it.wishId, "kind" to it.kind) },
            "verdicts" to output.verdicts.sortedWith(compareBy({ it.wishId.toString() }, { it.optionId.toString() })),
        ))
    }

    private fun offerPayload(offer: Offer): String = hashing.canonical(mapOf(
        "version" to 1, "workspaceId" to offer.workspaceId, "offerId" to offer.id, "optionId" to offer.optionId,
        "analysisRunId" to offer.analysisRunId, "title" to offer.title, "specText" to offer.specText,
        "chosenVariants" to offer.chosenVariants, "unitPriceMinor" to offer.unitPrice, "currency" to offer.currency,
        "deliveryTerms" to offer.deliveryTerms, "shipRegion" to offer.shipRegion, "minBatch" to offer.minBatch,
        "capacity" to offer.capacity, "maxQtyPerPledge" to offer.maxQtyPerPledge,
        "supportingWishIds" to offer.supportingWishIds.sortedBy { it.toString() },
    ))

    private fun storeSource(workspaceId: UUID, type: String, id: UUID, payload: String) {
        val existing = source(type, id, workspaceId)
        if (existing == null) entities.persist(DecisionSourceSnapshot(workspaceId, type, id, payload, hashing.sha256(payload)))
        else check(existing.payload == payload && existing.hash == hashing.sha256(payload)) { "An immutable snapshot already exists" }
    }

    private fun append(record: PledgeDecisionRecord, kind: String, payload: String) {
        val position = record.entryCount + 1
        val hash = hashing.entry(record, position, kind, payload, record.lastHash)
        entities.persist(DecisionRecordEntry(record.pledgeId, position, kind, payload, record.lastHash, hash))
        record.entryCount = position
        record.lastHash = hash
    }
}

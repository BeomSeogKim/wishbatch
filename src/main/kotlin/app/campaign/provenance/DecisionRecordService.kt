package app.campaign.provenance

import app.campaign.model.AnalysisRun
import app.campaign.model.Campaign
import app.campaign.model.Offer
import app.campaign.model.Pledge
import app.campaign.model.Wish
import app.campaign.port.AnalysisOutput
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Lifecycle writers join the caller's transaction; reads never create a baseline.
 * Call after saving the source in the same checkpoint, before it can be edited.
 * Existing snapshots cannot be replaced. Missing earlier hooks remain unavailable.
 */
@Service
@Transactional
class DecisionRecordService(private val entities: EntityManager, private val mapper: JsonMapper) {
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
        val payload = analysisPayload(run, wishes)
        require(payload == analysisPayload(saved, wishes))
        storeSource(run.workspaceId, "ANALYSIS", run.id, payload)
    }

    /** Call at publication. The offer ID is its version; the stored source hash is its terms hash. */
    fun recordOffer(offer: Offer) {
        entities.flush()
        val saved = requireNotNull(entities.find(Offer::class.java, offer.id, LockModeType.PESSIMISTIC_WRITE))
        require(saved.workspaceId == offer.workspaceId && offerPayload(saved) == offerPayload(offer))
        storeSource(offer.workspaceId, "OFFER", offer.id, offerPayload(offer))
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
        require(paymentPayload(saved) == paymentPayload(pledge))
        val campaign = requireNotNull(entities.find(Campaign::class.java, pledge.campaignId))
        val offer = requireNotNull(entities.find(Offer::class.java, campaign.offerId))
        var record = entities.find(PledgeDecisionRecord::class.java, pledge.id)
        if (record == null) {
            record = PledgeDecisionRecord(pledge.id, offer.workspaceId, campaign.id, offer.id)
            entities.persist(record)
            append(record, "WISH", hashing.canonical(mapOf(
                "availability" to "UNAVAILABLE", "reason" to "No wish association was recorded for this pledge.",
            )))
            val analysis = source("ANALYSIS", offer.analysisRunId, offer.workspaceId)
            val offerSnapshot = source("OFFER", offer.id, offer.workspaceId)
            append(record, "VERDICT", hashing.canonical(mapOf(
                "availability" to "UNAVAILABLE", "reason" to "No wish association was recorded for this pledge.",
                "analysisRunId" to offer.analysisRunId, "analysisSnapshotHash" to analysis?.hash,
                "model" to analysis?.let { mapper.readTree(it.payload).get("model").asString() },
                "inputHash" to analysis?.let { mapper.readTree(it.payload).get("inputHash").asString() },
            )))
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
        val payment = paymentPayload(pledge)
        if (entries.lastOrNull { it.kind == "PAYMENT" }?.payload != payment) append(record, "PAYMENT", payment)
    }

    internal fun source(type: String, id: UUID, workspaceId: UUID): DecisionSourceSnapshot? = entities.createQuery(
        "select s from DecisionSourceSnapshot s where s.sourceType = :type and s.sourceId = :id and s.workspaceId = :workspace",
        DecisionSourceSnapshot::class.java,
    ).setParameter("type", type).setParameter("id", id).setParameter("workspace", workspaceId)
        .resultList.singleOrNull()

    internal fun entries(pledgeId: UUID): List<DecisionRecordEntry> = entities.createQuery(
        "select e from DecisionRecordEntry e where e.pledgeId = :id order by e.position", DecisionRecordEntry::class.java,
    ).setParameter("id", pledgeId).resultList

    internal fun chainValid(record: PledgeDecisionRecord, entries: List<DecisionRecordEntry>): Boolean = runCatching {
        var previous: String? = null
        entries.forEachIndexed { index, entry ->
            check(entry.pledgeId == record.pledgeId && entry.position == index + 1 && entry.previousHash == previous)
            check(hashing.canonical(mapper.readTree(entry.payload)) == entry.payload)
            check(entry.hash == hashing.entry(record, entry.position, entry.kind, entry.payload, previous))
            previous = entry.hash
        }
        entries.isNotEmpty() && entries.size == record.entryCount && previous == record.lastHash
    }.getOrDefault(false)

    internal fun sourcesValid(record: PledgeDecisionRecord, entries: List<DecisionRecordEntry>): Boolean = runCatching {
        val offerEntry = mapper.readTree(entries.single { it.kind == "OFFER" }.payload)
        val verdictEntry = mapper.readTree(entries.single { it.kind == "VERDICT" }.payload)
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

    internal fun paymentPayload(pledge: Pledge): String = hashing.canonical(mapOf(
        "pledgeId" to pledge.id, "campaignId" to pledge.campaignId, "createdAt" to pledge.createdAt.toString(),
        "quantity" to pledge.qty, "amountMinor" to pledge.amount, "currency" to pledge.currency,
        "orderId" to pledge.paypalOrderId, "authorizationId" to pledge.authorizationId,
        "authorizationExpiresAt" to pledge.authorizationExpiresAt?.toString(), "captureId" to pledge.captureId,
        "refundId" to pledge.refundId, "state" to pledge.state.name,
        "simulatedCaptureFailure" to (pledge.lastError == "SIMULATED_CAPTURE_FAILURE"),
    ))

    private fun analysisPayload(run: AnalysisRun, wishes: List<Wish>): String {
        val output = mapper.readValue(run.resultJson, AnalysisOutput::class.java)
        return hashing.canonical(mapOf(
            "version" to 1, "workspaceId" to run.workspaceId, "runId" to run.id, "model" to run.model,
            "inputHash" to run.inputHash, "createdAt" to run.createdAt.toString(), "seed" to run.isSeed,
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

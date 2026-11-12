package app.campaign.provenance

import app.campaign.model.Pledge
import app.campaign.store.CampaignRepository
import app.campaign.store.OfferRepository
import app.campaign.store.PledgeRepository
import app.campaign.store.WorkspaceRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

data class DecisionSourceView(val sourceType: String, val sourceId: UUID, val hash: String, val payload: JsonNode, val intact: Boolean)
data class DecisionEntryView(
    val position: Int, val kind: String, val payload: JsonNode, val previousHash: String?, val hash: String,
)
data class PledgeDecisionView(
    val pledgeId: UUID, val campaignId: UUID, val offerId: UUID?, val integrity: String, val issues: List<String>,
    val entryCount: Int, val lastHash: String?, val entries: List<DecisionEntryView>,
)
data class DecisionRecordDocument(
    val version: Int = 1,
    val hashAlgorithm: String = "SHA-256; sorted object keys, ordered arrays, UTF-8 JSON",
    val workspaceId: UUID,
    val sources: List<DecisionSourceView>,
    val pledges: List<PledgeDecisionView>,
)

@Service
@Transactional(readOnly = true)
class DecisionRecordQuery(
    private val entities: EntityManager,
    private val workspaces: WorkspaceRepository,
    private val offers: OfferRepository,
    private val campaigns: CampaignRepository,
    private val pledges: PledgeRepository,
    private val records: DecisionRecordService,
    private val mapper: JsonMapper,
) {
    /** Ownership is resolved before loading records; this read does not reconcile or write. */
    fun findByToken(token: String): DecisionRecordDocument? {
        val workspace = workspaces.findByToken(token) ?: return null
        val snapshots = entities.createQuery(
            "select s from DecisionSourceSnapshot s where s.workspaceId = :id order by s.sourceType, s.sourceId",
            DecisionSourceSnapshot::class.java,
        ).setParameter("id", workspace.id).resultList
        val hashing = DecisionRecordHash(mapper)
        val sources = snapshots.map {
            DecisionSourceView(it.sourceType, it.sourceId, it.hash, parse(it.payload), hashing.sha256(it.payload) == it.hash)
        }
        val campaignIds = campaigns.findByOfferIdIn(offers.findByWorkspaceId(workspace.id).map { it.id }).map { it.id }
        val current = campaignIds.flatMap { pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(it) }
            .associateBy { it.id }
        val heads = entities.createQuery(
            "select r from PledgeDecisionRecord r where r.workspaceId = :id", PledgeDecisionRecord::class.java,
        ).setParameter("id", workspace.id).resultList.associateBy { it.pledgeId }
        val views = (current.keys + heads.keys).sortedBy { it.toString() }.map { id ->
            val head = heads[id]
            val pledge = current[id]
            if (head == null) {
                PledgeDecisionView(id, requireNotNull(pledge).campaignId, null, "UNAVAILABLE",
                    listOf("No lifecycle snapshot was recorded. Wish association and verdict are unavailable."), 0, null, emptyList())
            } else view(head, pledge)
        }
        return DecisionRecordDocument(workspaceId = workspace.id, sources = sources, pledges = views)
    }

    private fun view(head: PledgeDecisionRecord, pledge: Pledge?): PledgeDecisionView {
        val entries = records.entries(head.pledgeId)
        val issues = buildList {
            if (!records.chainValid(head, entries)) add("Stored hash chain failed integrity verification.")
            if (!records.sourcesValid(head, entries)) add("Analysis input or offer differs from its recorded snapshot, or a source is missing.")
            if (pledge == null || pledge.campaignId != head.campaignId ||
                entries.lastOrNull { it.kind == "PAYMENT" }?.payload != records.paymentPayload(pledge)
            ) add("Pledge differs from its last recorded checkpoint, or is missing.")
        }
        return PledgeDecisionView(head.pledgeId, head.campaignId, head.offerId, if (issues.isEmpty()) "INTACT" else "CHANGED",
            issues, head.entryCount, head.lastHash, entries.map {
                DecisionEntryView(it.position, it.kind, parse(it.payload), it.previousHash, it.hash)
            })
    }

    private fun parse(payload: String): JsonNode = runCatching { mapper.readTree(payload) }
        .getOrElse { mapper.valueToTree("Invalid stored JSON") }
}

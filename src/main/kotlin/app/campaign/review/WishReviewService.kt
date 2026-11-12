package app.campaign.review

import app.campaign.model.AnalysisRun
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.WishKind
import app.campaign.model.Workspace
import app.campaign.port.AnalysisOutput
import app.campaign.port.NotServedGroup
import app.campaign.port.WishVerdict
import app.campaign.workspace.WorkspaceNotFoundException
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.UUID

class InvalidWishReviewException : RuntimeException()

@Service
@Transactional(readOnly = true)
class WishReviewService(
    private val entityManager: EntityManager,
    private val mapper: JsonMapper,
    private val clock: Clock,
) {
    fun history(workspaceId: UUID, runId: UUID): List<WishReviewOverride> = entityManager.createQuery(
        "select r from WishReviewOverride r where r.workspaceId = :workspaceId and r.runId = :runId " +
            "order by r.revision, r.createdAt, r.id", WishReviewOverride::class.java,
    ).setParameter("workspaceId", workspaceId).setParameter("runId", runId).resultList

    @Transactional
    fun decide(
        token: String,
        runId: UUID,
        wishId: UUID,
        optionId: UUID,
        verdict: CompatibilityVerdict,
        reason: String,
    ): WishReviewOverride {
        val workspace = entityManager.createQuery(
            "select w from Workspace w where w.token = :token", Workspace::class.java,
        ).setParameter("token", token).setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .resultList.singleOrNull() ?: throw WorkspaceNotFoundException()
        val run = entityManager.find(AnalysisRun::class.java, runId)
            ?.takeIf { it.workspaceId == workspace.id } ?: throw WorkspaceNotFoundException()
        val original = mapper.readValue(run.resultJson, AnalysisOutput::class.java)
        if (wishId !in original.wishes.map { it.wishId } || optionId !in original.optionIds) {
            throw WorkspaceNotFoundException()
        }
        val trimmed = reason.trim()
        if (verdict !in setOf(CompatibilityVerdict.COMPATIBLE, CompatibilityVerdict.INCOMPATIBLE) ||
            trimmed.isEmpty() || trimmed.length > 500
        ) throw InvalidWishReviewException()
        val previous = history(workspace.id, runId).filter { it.wishId == wishId && it.optionId == optionId }
            .maxByOrNull { it.revision }
        return WishReviewOverride(
            workspace.id, runId, wishId, optionId, verdict,
            previous?.verdict ?: original.verdicts.singleOrNull { it.wishId == wishId && it.optionId == optionId }?.verdict,
            trimmed, clock.instant(), (previous?.revision ?: 0) + 1,
        ).also(entityManager::persist)
    }
}

/** Projects maker decisions without rewriting the stored run. */
fun reviewedAnalysis(output: AnalysisOutput, history: List<WishReviewOverride>): AnalysisOutput {
    if (history.isEmpty()) return output
    val latest = history.groupBy { it.wishId to it.optionId }.mapValues { (_, rows) -> rows.maxBy { it.revision } }
    val wishes = output.wishes.map { wish ->
        if (latest.keys.any { it.first == wish.wishId }) wish.copy(kind = WishKind.REQUEST) else wish
    }
    val originalVerdicts = output.verdicts.associateBy { it.wishId to it.optionId }
    val verdicts = wishes.filter { it.kind == WishKind.REQUEST }.flatMap { wish ->
        output.optionIds.map { optionId ->
            val key = wish.wishId to optionId
            val decision = latest[key]
            when {
                decision != null -> WishVerdict(wish.wishId, optionId, decision.verdict, decision.reason)
                originalVerdicts[key] != null -> originalVerdicts.getValue(key)
                else -> WishVerdict(wish.wishId, optionId, CompatibilityVerdict.CONDITIONAL, "Not reviewed for this option.")
            }
        }
    }
    val notServed = output.proposal?.let { proposal ->
        verdicts.filter { it.optionId == proposal.optionId && it.verdict == CompatibilityVerdict.INCOMPATIBLE }
            .groupBy { it.reason }.map { (reason, rows) ->
                NotServedGroup("Not served by this option", rows.map { it.wishId }, reason)
            }
    }.orEmpty()
    return output.copy(wishes = wishes, verdicts = verdicts, notServed = notServed)
}

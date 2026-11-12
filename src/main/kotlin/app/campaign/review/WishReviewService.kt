package app.campaign.review

import app.campaign.model.AnalysisRun
import app.campaign.followup.FollowupAnswerRecord
import app.campaign.followup.FollowupQuestion
import app.campaign.followup.FollowupQuestions
import app.campaign.followup.FollowupReviewRecorded
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.model.Workspace
import app.campaign.port.AnalysisOutput
import app.campaign.port.NotServedGroup
import app.campaign.port.WishVerdict
import app.campaign.workspace.WorkspaceNotFoundException
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Service
import org.springframework.context.ApplicationEventPublisher
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
    private val events: ApplicationEventPublisher,
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

    /** Derives a decision only from persisted answers to approved questions. */
    @Transactional
    fun recordFanResolution(token: String, answerId: UUID): WishReviewOverride? {
        val workspace = entityManager.createQuery(
            "select w from Workspace w where w.token = :token", Workspace::class.java,
        ).setParameter("token", token).setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .resultList.singleOrNull() ?: throw WorkspaceNotFoundException()
        val answer = entityManager.find(FollowupAnswerRecord::class.java, answerId)
            ?.takeIf { it.workspaceId == workspace.id } ?: throw WorkspaceNotFoundException()
        val question = entityManager.find(FollowupQuestion::class.java, answer.questionId)
            ?: throw WorkspaceNotFoundException()
        if (!question.approved || question.workspaceId != workspace.id || question.runId != answer.runId ||
            question.optionId != answer.optionId || answer.wishId !in question.wishIds || answer.choice !in question.choices
        ) throw InvalidWishReviewException()
        val wish = entityManager.find(Wish::class.java, answer.wishId)
            ?.takeIf { it.workspaceId == workspace.id && !it.hidden } ?: throw WorkspaceNotFoundException()
        val run = entityManager.createQuery(
            "select r from AnalysisRun r where r.workspaceId = :workspaceId " +
                "order by r.isSeed asc, r.createdAt desc, r.id desc", AnalysisRun::class.java,
        ).setParameter("workspaceId", workspace.id).setMaxResults(1).resultList.singleOrNull()
            ?.takeIf { it.id == answer.runId } ?: throw InvalidWishReviewException()
        val history = history(workspace.id, run.id)
        history.singleOrNull { it.answerId == answerId }?.let { return it }
        val previous = history.filter { it.wishId == wish.id && it.optionId == answer.optionId }.maxByOrNull { it.revision }
        if (previous?.source == "MAKER") return null
        if (previous?.source == "FAN_ANSWER") return previous
        val original = mapper.readValue(run.resultJson, AnalysisOutput::class.java)
        val needs = original.wishes.singleOrNull { it.wishId == wish.id }?.needs.orEmpty()
        if (!FollowupQuestions.grounded(needs, wish)) return null
        val option = entityManager.find(Option::class.java, answer.optionId)
            ?.takeIf { it.workspaceId == workspace.id } ?: throw WorkspaceNotFoundException()
        val questions = entityManager.createQuery(
            "select q from FollowupQuestion q where q.workspaceId = :workspaceId and q.runId = :runId " +
                "and q.optionId = :optionId and q.approved = true", FollowupQuestion::class.java,
        ).setParameter("workspaceId", workspace.id).setParameter("runId", run.id)
            .setParameter("optionId", option.id).resultList
        val answers = entityManager.createQuery(
            "select a from FollowupAnswerRecord a where a.workspaceId = :workspaceId and a.runId = :runId " +
                "and a.optionId = :optionId and a.wishId = :wishId", FollowupAnswerRecord::class.java,
        ).setParameter("workspaceId", workspace.id).setParameter("runId", run.id)
            .setParameter("optionId", option.id).setParameter("wishId", wish.id).resultList
        val resolution = try {
            FollowupQuestions.resolve(run.id, wish.id, questions.map { it.approvedQuestion() },
                answers.map { it.answer() }, option, original)
        } catch (_: IllegalArgumentException) {
            throw InvalidWishReviewException()
        }
        if (resolution.verdict == CompatibilityVerdict.CONDITIONAL) return null
        return WishReviewOverride(
            workspace.id, run.id, wish.id, option.id, resolution.verdict,
            CompatibilityVerdict.CONDITIONAL, "Fan confirmed the allowed variants for the recorded conditions.",
            clock.instant(), (previous?.revision ?: 0) + 1, source = "FAN_ANSWER", answerId = answer.id,
        ).also {
            entityManager.persist(it)
            events.publishEvent(FollowupReviewRecorded(it, answers.toList(),
                questions.filter { question -> answers.any { it.questionId == question.id } }))
        }
    }
}

/** Projects recorded decisions without rewriting the stored run. */
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

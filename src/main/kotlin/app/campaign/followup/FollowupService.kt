package app.campaign.followup

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.analysisrun.StoredAnalysisRun
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Option
import app.campaign.model.Workspace
import app.campaign.port.AnalysisFailure
import app.campaign.port.AnalysisOutput
import app.campaign.review.WishReviewService
import app.campaign.workspace.WorkspaceNotFoundException
import app.campaign.workspace.WorkspaceService
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

class InvalidFollowupException : RuntimeException()

enum class FollowupPageState { READY, UNAVAILABLE, STALE, NO_ELIGIBLE, FAILED }

data class FollowupPage(
    val runId: UUID?,
    val option: Option?,
    val questions: List<FollowupQuestion>,
    val failure: String?,
    val drafted: Boolean = false,
    val state: FollowupPageState = FollowupPageState.READY,
)

@Service
@Transactional(readOnly = true)
class FollowupService(
    private val entityManager: EntityManager,
    private val workspaces: WorkspaceService,
    private val runs: AnalysisRunService,
    private val drafter: FollowupDrafter,
    private val reviews: WishReviewService,
    private val clock: Clock,
) {
    fun makerPage(token: String): FollowupPage {
        val stored = runs.latest(token)
            ?: return FollowupPage(null, null, emptyList(), null, state = FollowupPageState.UNAVAILABLE)
        val option = workspaces.listOptions(token).singleOrNull { it.id == stored.output.proposal?.optionId }
        val attempt = entityManager.find(FollowupDraftAttempt::class.java, stored.run.id)
        val questions = questions(stored)
        val state = when {
            option == null -> FollowupPageState.UNAVAILABLE
            attempt?.failure != null -> FollowupPageState.FAILED
            questions.any { question ->
                !validQuestion(token, stored, option, question.draft()) || question.wishIds.any { wishId ->
                    stored.overrides.filter { it.wishId == wishId && it.optionId == question.optionId }
                        .maxByOrNull { it.revision }?.source == "MAKER"
                }
            } -> FollowupPageState.STALE
            questions.isNotEmpty() -> FollowupPageState.READY
            attempt != null || eligibleOutput(token, stored, option).wishes.isEmpty() -> FollowupPageState.NO_ELIGIBLE
            else -> FollowupPageState.READY
        }
        return FollowupPage(stored.run.id, option, questions, attempt?.failure, attempt != null, state)
    }

    @Transactional
    fun draft(token: String): FollowupDraftResult {
        lock(token)
        val stored = runs.latest(token) ?: throw InvalidFollowupException()
        entityManager.find(FollowupDraftAttempt::class.java, stored.run.id)?.let { attempt ->
            return if (attempt.failure != null) FollowupDraftResult.Failed(AnalysisFailure.valueOf(attempt.failure!!))
            else FollowupDraftResult.Drafted(questions(stored).map { it.draft() }, attempt.model ?: stored.run.model)
        }
        val option = workspaces.listOptions(token).singleOrNull { it.id == stored.output.proposal?.optionId }
            ?: throw InvalidFollowupException()
        val output = eligibleOutput(token, stored, option)
        if (output.wishes.isEmpty()) return FollowupDraftResult.Drafted(emptyList(), stored.run.model)
        val result = drafter.draft(option, output)
        if (result is FollowupDraftResult.Failed && result.reason in setOf(AnalysisFailure.NOT_CONFIGURED, AnalysisFailure.RUN_LIMIT_REACHED)) {
            return result
        }
        val attempt = FollowupDraftAttempt(stored.run.id, stored.run.workspaceId, clock.instant())
        when (result) {
            is FollowupDraftResult.Failed -> attempt.failure = result.reason.name
            is FollowupDraftResult.Drafted -> {
                try {
                    FollowupQuestions.validate(result.drafts, option, output)
                    require(result.model.isNotBlank())
                } catch (_: IllegalArgumentException) {
                    attempt.failure = AnalysisFailure.INVALID_OUTPUT.name
                    entityManager.persist(attempt)
                    return FollowupDraftResult.Failed(AnalysisFailure.INVALID_OUTPUT)
                }
                attempt.model = result.model
                result.drafts.forEach { draft ->
                    entityManager.persist(FollowupQuestion(stored.run.workspaceId, stored.run.id, option.id,
                        draft.attribute, draft.variantKey, draft.text, draft.choices.toList(), draft.wishIds.toList(), clock.instant()))
                }
            }
        }
        entityManager.persist(attempt)
        return result
    }

    @Transactional
    fun approve(token: String, questionId: UUID, text: String, choices: List<String>): FollowupQuestion {
        val workspace = lock(token)
        val stored = runs.latest(token) ?: throw InvalidFollowupException()
        val question = question(workspace.id, questionId, stored)
        val edited = question.draft().copy(text = text.trim(), choices = choices.toList())
        if (question.approved) {
            if (edited != question.draft()) throw InvalidFollowupException()
            return question
        }
        val option = workspaces.listOptions(token).single { it.id == question.optionId }
        if (!validQuestion(token, stored, option, edited) || edited.wishIds.any { wishId ->
                stored.output.verdicts.none {
                    it.wishId == wishId && it.optionId == option.id && it.verdict == CompatibilityVerdict.CONDITIONAL
                }
            }) {
            throw InvalidFollowupException()
        }
        question.text = edited.text
        question.choices = edited.choices
        question.approved = true
        return question
    }

    fun fanQuestions(token: String, wishId: UUID): List<FollowupQuestion> {
        val stored = visibleWish(token, wishId)
        val options = workspaces.listOptions(token).associateBy { it.id }
        val answered = answerRows(stored, wishId).map { it.questionId }.toSet()
        return questions(stored).filter { question ->
            val option = options[question.optionId]
            question.approved && wishId in question.wishIds && option != null &&
                validQuestion(token, stored, option, question.draft().copy(wishIds = listOf(wishId))) && (question.id in answered ||
                stored.output.verdicts.any { it.wishId == wishId && it.optionId == question.optionId && it.verdict == CompatibilityVerdict.CONDITIONAL })
        }
    }

    fun answers(token: String, wishId: UUID): List<FollowupAnswerRecord> = answerRows(visibleWish(token, wishId), wishId)

    @Transactional
    fun answer(token: String, questionId: UUID, wishId: UUID, choice: String): FollowupAnswerRecord {
        val workspace = lock(token)
        val stored = visibleWish(token, wishId)
        val question = question(workspace.id, questionId, stored)
        if (!question.approved || wishId !in question.wishIds || choice !in question.choices) throw InvalidFollowupException()
        answerRows(stored, wishId).singleOrNull { it.questionId == questionId }?.let {
            if (it.choice != choice) throw InvalidFollowupException()
            return it
        }
        val option = workspaces.listOptions(token).single { it.id == question.optionId }
        if (!validQuestion(token, stored, option, question.draft().copy(wishIds = listOf(wishId)))) {
            throw InvalidFollowupException()
        }
        val answer = FollowupAnswerRecord(workspace.id, stored.run.id, question.optionId, question.id, wishId, choice, clock.instant())
        entityManager.persist(answer)
        reviews.recordFanResolution(token, answer.id)
        return answer
    }

    private fun eligibleOutput(token: String, stored: StoredAnalysisRun, option: Option): AnalysisOutput {
        val visible = workspaces.listWishes(token, includeHidden = false).associateBy { it.id }
        val eligible = FollowupQuestions.eligibleWishIds(option, stored.output)
        val wishes = stored.originalOutput.wishes.filter { analyzed ->
            analyzed.wishId in eligible && visible[analyzed.wishId]?.let {
                FollowupQuestions.grounded(analyzed.needs, it)
            } == true
        }
        val ids = wishes.map { it.wishId }.toSet()
        return stored.originalOutput.copy(wishes = wishes,
            verdicts = stored.originalOutput.verdicts.filter { it.wishId in ids })
    }

    private fun validQuestion(token: String, stored: StoredAnalysisRun, option: Option, draft: FollowupDraft): Boolean {
        val visible = workspaces.listWishes(token, includeHidden = false).associateBy { it.id }
        return try {
            FollowupQuestions.validate(listOf(draft), option, stored.originalOutput)
            draft.wishIds.all { id ->
                val analyzed = stored.originalOutput.wishes.single { it.wishId == id }
                visible[id]?.let { FollowupQuestions.grounded(analyzed.needs, it) } == true
            }
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun questions(stored: StoredAnalysisRun): List<FollowupQuestion> = entityManager.createQuery(
        "select q from FollowupQuestion q where q.workspaceId = :workspaceId and q.runId = :runId order by q.createdAt, q.id",
        FollowupQuestion::class.java,
    ).setParameter("workspaceId", stored.run.workspaceId).setParameter("runId", stored.run.id).resultList

    private fun answerRows(stored: StoredAnalysisRun, wishId: UUID): List<FollowupAnswerRecord> = entityManager.createQuery(
        "select a from FollowupAnswerRecord a where a.workspaceId = :workspaceId and a.runId = :runId " +
            "and a.wishId = :wishId order by a.createdAt, a.id", FollowupAnswerRecord::class.java,
    ).setParameter("workspaceId", stored.run.workspaceId).setParameter("runId", stored.run.id)
        .setParameter("wishId", wishId).resultList

    private fun visibleWish(token: String, wishId: UUID): StoredAnalysisRun {
        if (workspaces.listWishes(token, includeHidden = false).none { it.id == wishId }) throw WorkspaceNotFoundException()
        return runs.latest(token) ?: throw WorkspaceNotFoundException()
    }

    private fun question(workspaceId: UUID, id: UUID, stored: StoredAnalysisRun): FollowupQuestion =
        entityManager.find(FollowupQuestion::class.java, id)?.takeIf {
            it.workspaceId == workspaceId && it.runId == stored.run.id
        } ?: throw WorkspaceNotFoundException()

    private fun lock(token: String): Workspace = entityManager.createQuery(
        "select w from Workspace w where w.token = :token", Workspace::class.java,
    ).setParameter("token", token).setLockMode(LockModeType.PESSIMISTIC_WRITE)
        .resultList.singleOrNull() ?: throw WorkspaceNotFoundException()
}

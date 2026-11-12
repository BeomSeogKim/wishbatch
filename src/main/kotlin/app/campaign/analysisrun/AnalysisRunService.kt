package app.campaign.analysisrun

import app.campaign.model.AnalysisRun
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.Workspace
import app.campaign.port.AnalysisFailure
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalysisResult
import app.campaign.port.AnalysisValidationFlag
import app.campaign.port.WishAnalyzer
import app.campaign.provenance.DecisionRecordService
import app.campaign.review.WishReviewOverride
import app.campaign.review.WishReviewService
import app.campaign.review.reviewedAnalysis
import app.campaign.rules.analysisInputHash
import app.campaign.workspace.WorkspaceNotFoundException
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

data class StoredAnalysisRun(
    val run: AnalysisRun,
    val output: AnalysisOutput,
    val flags: List<AnalysisValidationFlag>,
    val newWishCount: Int,
    val originalOutput: AnalysisOutput = output,
    val overrides: List<WishReviewOverride> = emptyList(),
)

sealed interface AnalysisRunResult {
    data class Completed(val stored: StoredAnalysisRun) : AnalysisRunResult
    data class Failed(
        val reason: AnalysisFailure,
        val flags: List<AnalysisValidationFlag> = emptyList(),
    ) : AnalysisRunResult
    data object NoVisibleWishes : AnalysisRunResult
}

@Service
@Transactional(readOnly = true)
class AnalysisRunService(
    private val entityManager: EntityManager,
    private val analyzer: WishAnalyzer,
    private val mapper: JsonMapper,
    private val seed: StoredSeedAnalysis,
    private val reviews: WishReviewService,
    private val records: DecisionRecordService,
) {
    fun latest(token: String): StoredAnalysisRun? {
        val workspace = workspace(token)
        val run = entityManager.createQuery(
            "select r from AnalysisRun r where r.workspaceId = :workspaceId " +
                "order by r.isSeed asc, r.createdAt desc, r.id desc", AnalysisRun::class.java,
        ).setParameter("workspaceId", workspace.id).setMaxResults(1).resultList.singleOrNull()
            ?: return null
        return decode(workspace, run)
    }

    fun find(token: String, id: UUID): StoredAnalysisRun? {
        val workspace = workspace(token)
        val run = entityManager.createQuery(
            "select r from AnalysisRun r where r.workspaceId = :workspaceId and r.id = :id", AnalysisRun::class.java,
        ).setParameter("workspaceId", workspace.id).setParameter("id", id).resultList.singleOrNull()
            ?: return null
        return decode(workspace, run)
    }

    fun liveRunCount(token: String): Long = liveRunCount(workspace(token))

    @Transactional
    fun run(token: String): AnalysisRunResult {
        // Intake uses this same lock, keeping the snapshot and quota stable during the call.
        val workspace = workspace(token, lock = true)
        if (liveRunCount(workspace) >= 10) return AnalysisRunResult.Failed(AnalysisFailure.RUN_LIMIT_REACHED)
        val wishes = wishes(workspace).filterNot { it.hidden }
        if (wishes.isEmpty()) return AnalysisRunResult.NoVisibleWishes
        val options = entityManager.createQuery(
            "select o from Option o where o.workspaceId = :workspaceId order by o.name", Option::class.java,
        ).setParameter("workspaceId", workspace.id).resultList
        val result = analyzer.analyze(options, wishes)
        if (result !is AnalysisResult.Failure ||
            result.reason !in setOf(AnalysisFailure.NOT_CONFIGURED, AnalysisFailure.RUN_LIMIT_REACHED)
        ) {
            val quota = entityManager.find(AnalysisRunQuota::class.java, workspace.id)
                ?: AnalysisRunQuota(workspace.id).also(entityManager::persist)
            quota.liveRuns++
        }
        return when (result) {
            is AnalysisResult.Failure -> AnalysisRunResult.Failed(result.reason, result.flags)
            is AnalysisResult.Success -> {
                val output = result.validated.output
                if (result.inputHash != analysisInputHash(options, wishes) ||
                    output.optionIds.toSet() != options.map { it.id }.toSet() ||
                    output.wishes.map { it.wishId }.toSet() != wishes.map { it.id }.toSet()
                ) {
                    AnalysisRunResult.Failed(AnalysisFailure.INVALID_OUTPUT)
                } else {
                    AnalysisRunResult.Completed(decode(workspace, persist(workspace, result, wishes, isSeed = false)))
                }
            }
        }
    }

    @Transactional
    fun attachSeed(workspace: Workspace, options: List<Option>, wishes: List<Wish>) {
        persist(workspace, seed.forWorkspace(workspace, options, wishes), wishes, isSeed = true)
    }

    private fun persist(workspace: Workspace, result: AnalysisResult.Success, wishes: List<Wish>, isSeed: Boolean): AnalysisRun =
        AnalysisRun(
            workspaceId = workspace.id,
            model = result.model,
            inputHash = result.inputHash,
            resultJson = mapper.writeValueAsString(result.validated.output),
            validationFlags = result.validated.flags.map { mapper.writeValueAsString(it) },
            isSeed = isSeed,
            createdAt = result.completedAt,
        ).also {
            entityManager.persist(it)
            records.recordAnalysis(it, wishes)
        }

    private fun decode(workspace: Workspace, run: AnalysisRun): StoredAnalysisRun {
        val output = mapper.readValue(run.resultJson, AnalysisOutput::class.java)
        val included = output.wishes.map { it.wishId }.toSet()
        val since = if (run.isSeed) workspace.createdAt else run.createdAt
        val overrides = reviews.history(workspace.id, run.id)
        return StoredAnalysisRun(
            run = run,
            output = reviewedAnalysis(output, overrides),
            flags = run.validationFlags.map { mapper.readValue(it, AnalysisValidationFlag::class.java) },
            newWishCount = wishes(workspace).count { !it.hidden && it.id !in included && !it.createdAt.isBefore(since) },
            originalOutput = output,
            overrides = overrides,
        )
    }

    private fun wishes(workspace: Workspace): List<Wish> = entityManager.createQuery(
        "select w from Wish w where w.workspaceId = :workspaceId", Wish::class.java,
    ).setParameter("workspaceId", workspace.id).resultList

    private fun liveRunCount(workspace: Workspace): Long =
        entityManager.find(AnalysisRunQuota::class.java, workspace.id)?.liveRuns ?: 0

    private fun workspace(token: String, lock: Boolean = false): Workspace {
        if (!Regex("[A-Za-z0-9_-]{43}").matches(token)) throw WorkspaceNotFoundException()
        val query = entityManager.createQuery(
            "select w from Workspace w where w.token = :token", Workspace::class.java,
        ).setParameter("token", token).setMaxResults(1)
        if (lock) query.setLockMode(LockModeType.PESSIMISTIC_WRITE)
        return query.resultList.singleOrNull() ?: throw WorkspaceNotFoundException()
    }
}

package app.campaign.analysisrun

import app.campaign.model.AnalysisRun
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.Workspace
import app.campaign.port.AnalysisFailure
import app.campaign.port.AnalysisObserver
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalysisResult
import app.campaign.port.AnalysisValidationFlag
import app.campaign.port.WishAnalyzer
import app.campaign.progress.AnalysisProgressStore
import app.campaign.progress.AnalysisProgressView
import app.campaign.progress.AnalysisWork
import app.campaign.provenance.DecisionRecordService
import app.campaign.review.WishReviewOverride
import app.campaign.review.WishReviewService
import app.campaign.review.reviewedAnalysis
import app.campaign.rules.analysisInputHash
import app.campaign.workspace.WorkspaceNotFoundException
import jakarta.annotation.PreDestroy
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

private sealed interface AnalysisPreparation {
    data class Ready(val work: AnalysisWork, val job: AnalysisProgressView) : AnalysisPreparation
    data class Existing(val job: AnalysisProgressView) : AnalysisPreparation
    data class Rejected(val result: AnalysisRunResult) : AnalysisPreparation
}

sealed interface AnalysisSubmission {
    data class Started(val job: AnalysisProgressView, val completion: Future<AnalysisRunResult>) : AnalysisSubmission
    data class Existing(val job: AnalysisProgressView) : AnalysisSubmission
    data class Rejected(val result: AnalysisRunResult) : AnalysisSubmission
    data object Busy : AnalysisSubmission
}

@Service
class AnalysisRunService(
    private val entityManager: EntityManager,
    private val analyzer: WishAnalyzer,
    private val mapper: JsonMapper,
    private val seed: StoredSeedAnalysis,
    private val reviews: WishReviewService,
    private val records: DecisionRecordService,
    private val progress: AnalysisProgressStore,
    manager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(manager)
    private val slots = Semaphore(4)
    private val executor = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2)) { task ->
        Thread(task, "analysis-progress").apply { isDaemon = true }
    }

    @Transactional(readOnly = true)
    fun latest(token: String): StoredAnalysisRun? {
        val workspace = workspace(token)
        val run = entityManager.createQuery(
            "select r from AnalysisRun r where r.workspaceId = :workspaceId " +
                "order by r.isSeed asc, r.createdAt desc, r.id desc", AnalysisRun::class.java,
        ).setParameter("workspaceId", workspace.id).setMaxResults(1).resultList.singleOrNull()
            ?: return null
        return decode(workspace, run)
    }

    @Transactional(readOnly = true)
    fun find(token: String, id: UUID): StoredAnalysisRun? {
        val workspace = workspace(token)
        val run = entityManager.createQuery(
            "select r from AnalysisRun r where r.workspaceId = :workspaceId and r.id = :id", AnalysisRun::class.java,
        ).setParameter("workspaceId", workspace.id).setParameter("id", id).resultList.singleOrNull()
            ?: return null
        return decode(workspace, run)
    }

    @Transactional(readOnly = true)
    fun liveRunCount(token: String): Long = liveRunCount(workspace(token))

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun run(token: String): AnalysisRunResult = when (val submitted = submit(token)) {
        is AnalysisSubmission.Started -> try {
            submitted.completion.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            AnalysisRunResult.Failed(AnalysisFailure.PROVIDER_FAILURE)
        } catch (_: ExecutionException) {
            AnalysisRunResult.Failed(AnalysisFailure.PROVIDER_FAILURE)
        }
        is AnalysisSubmission.Existing, AnalysisSubmission.Busy -> AnalysisRunResult.Failed(AnalysisFailure.RUN_LIMIT_REACHED)
        is AnalysisSubmission.Rejected -> submitted.result
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun submit(token: String): AnalysisSubmission {
        val existing = progress.latest(token)
        if (existing?.status in setOf("QUEUED", "RUNNING")) return AnalysisSubmission.Existing(requireNotNull(existing))
        if (!slots.tryAcquire()) return AnalysisSubmission.Busy
        var submitted = false
        try {
            return when (val prepared = prepare(token)) {
                is AnalysisPreparation.Existing -> AnalysisSubmission.Existing(prepared.job)
                is AnalysisPreparation.Rejected -> AnalysisSubmission.Rejected(prepared.result)
                is AnalysisPreparation.Ready -> {
                    val completion = FutureTask {
                        try { execute(prepared.work) } finally { slots.release() }
                    }
                    try {
                        executor.execute(completion)
                        submitted = true
                        AnalysisSubmission.Started(prepared.job, completion)
                    } catch (_: RejectedExecutionException) {
                        abandon(prepared.work)
                        AnalysisSubmission.Busy
                    }
                }
            }
        } finally {
            if (!submitted) slots.release()
        }
    }

    private fun prepare(token: String): AnalysisPreparation = requireNotNull(transaction.execute {
        val workspace = workspace(token, lock = true)
        progress.active(workspace.id)?.let { return@execute AnalysisPreparation.Existing(progress.view(it)) }
        if (liveRunCount(workspace) >= 10) {
            return@execute AnalysisPreparation.Rejected(AnalysisRunResult.Failed(AnalysisFailure.RUN_LIMIT_REACHED))
        }
        val wishes = wishes(workspace).filterNot { it.hidden }
        if (wishes.isEmpty()) return@execute AnalysisPreparation.Rejected(AnalysisRunResult.NoVisibleWishes)
        val options = entityManager.createQuery(
            "select o from Option o where o.workspaceId = :workspaceId order by o.name", Option::class.java,
        ).setParameter("workspaceId", workspace.id).resultList
        val quota = entityManager.find(AnalysisRunQuota::class.java, workspace.id)
            ?: AnalysisRunQuota(workspace.id).also(entityManager::persist)
        // Reserve before releasing the workspace lock, including jobs waiting in the bounded queue.
        quota.liveRuns++
        val job = progress.create(workspace.id, analysisInputHash(options, wishes), wishes.map { it.id })
        AnalysisPreparation.Ready(AnalysisWork(job.id, workspace.id, options, wishes), progress.view(job))
    })

    private fun execute(work: AnalysisWork): AnalysisRunResult {
        if (!progress.begin(work)) return AnalysisRunResult.Failed(AnalysisFailure.PROVIDER_FAILURE)
        val incomplete = AtomicBoolean()
        val result = try {
            analyzer.analyzeTracked(work.options, work.wishes, AnalysisObserver {
                if (!progress.observe(work, it)) incomplete.set(true)
            })
        } catch (_: Exception) {
            AnalysisResult.Failure(AnalysisFailure.PROVIDER_FAILURE)
        }
        return try {
            finish(work, result, incomplete.get())
        } catch (_: Exception) {
            // Failed persistence leaves the previous immutable analysis and provenance intact.
            finish(work, AnalysisResult.Failure(AnalysisFailure.PROVIDER_FAILURE), incomplete.get())
        }
    }

    private fun abandon(work: AnalysisWork): AnalysisRunResult =
        finish(work, AnalysisResult.Failure(AnalysisFailure.RUN_LIMIT_REACHED))

    private fun finish(
        work: AnalysisWork,
        result: AnalysisResult,
        checkpointIncomplete: Boolean = false,
    ): AnalysisRunResult = requireNotNull(transaction.execute {
        val workspace = entityManager.find(Workspace::class.java, work.workspaceId, LockModeType.PESSIMISTIC_WRITE)
        val checked = if (result is AnalysisResult.Success &&
            (result.inputHash != analysisInputHash(work.options, work.wishes) ||
                result.validated.output.optionIds.toSet() != work.options.map { it.id }.toSet() ||
                result.validated.output.wishes.map { it.wishId }.toSet() != work.wishes.map { it.id }.toSet())
        ) AnalysisResult.Failure(AnalysisFailure.INVALID_OUTPUT) else result
        if (checked is AnalysisResult.Failure &&
            checked.reason in setOf(AnalysisFailure.NOT_CONFIGURED, AnalysisFailure.RUN_LIMIT_REACHED)
        ) entityManager.find(AnalysisRunQuota::class.java, workspace.id).liveRuns--
        when (checked) {
            is AnalysisResult.Failure -> {
                progress.finish(work, checked, checkpointIncomplete = checkpointIncomplete)
                AnalysisRunResult.Failed(checked.reason, checked.flags)
            }
            is AnalysisResult.Success -> {
                val saved = persist(workspace, checked, work.wishes, isSeed = false)
                progress.finish(work, checked, saved.id, checkpointIncomplete)
                AnalysisRunResult.Completed(decode(workspace, saved))
            }
        }
    })

    @PreDestroy
    fun close() { executor.shutdownNow() }

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
        val since = if (run.isSeed) workspace.createdAt else progress.snapshotAt(workspace.id, run.id) ?: run.createdAt
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

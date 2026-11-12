package app.campaign.progress

import app.campaign.model.Workspace
import app.campaign.port.AnalysisProgressEvent
import app.campaign.port.AnalysisResult
import app.campaign.rules.analysisInputHash
import app.campaign.rules.optionWishCounts
import app.campaign.workspace.WorkspaceNotFoundException
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.UUID

@Service
class AnalysisProgressStore(
    private val entities: EntityManager,
    private val mapper: JsonMapper,
    private val clock: Clock,
    manager: PlatformTransactionManager,
) {
    val ownerId: UUID = UUID.randomUUID()
    private val checkpoint = TransactionTemplate(manager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }
    private val transaction = TransactionTemplate(manager)

    fun active(workspaceId: UUID): AnalysisProgressJob? = entities.createQuery(
        "select j from AnalysisProgressJob j where j.workspaceId = :workspace and j.status in ('QUEUED', 'RUNNING')",
        AnalysisProgressJob::class.java,
    ).setParameter("workspace", workspaceId).setMaxResults(1).resultList.singleOrNull()

    fun create(workspaceId: UUID, hash: String, wishIds: List<UUID>): AnalysisProgressJob = AnalysisProgressJob(
        workspaceId, ownerId, hash, mapper.writeValueAsString(wishIds), clock.instant(),
        stateJson = mapper.writeValueAsString(ProgressState()),
    ).also(entities::persist)

    fun begin(work: AnalysisWork): Boolean = transaction.execute {
        val job = entities.find(AnalysisProgressJob::class.java, work.jobId, LockModeType.PESSIMISTIC_WRITE)
        if (job == null || job.workspaceId != work.workspaceId || job.ownerId != ownerId || job.status != "QUEUED" ||
            job.inputHash != analysisInputHash(work.options, work.wishes) ||
            sources(job).toSet() != work.wishes.map { it.id }.toSet() ||
            work.wishes.any { it.workspaceId != work.workspaceId } || work.options.any { it.workspaceId != work.workspaceId }) false
        else { job.status = "RUNNING"; true }
    } == true

    fun observe(work: AnalysisWork, event: AnalysisProgressEvent): Boolean {
        // A progress checkpoint has its own rollback boundary and cannot poison result persistence.
        return runCatching {
            checkpoint.execute {
                val job = entities.find(AnalysisProgressJob::class.java, work.jobId, LockModeType.PESSIMISTIC_WRITE)
                if (job == null || job.workspaceId != work.workspaceId || job.ownerId != ownerId || job.status != "RUNNING") {
                    return@execute false
                }
                val state = state(job)
                if (state.events.size >= 2000) return@execute false
                val sourceIds = sources(job).toSet()
                val processed = state.processedWishIds + event.processedWishIds.filter { it in sourceIds }
                val batches = if (event.stage == "BATCH_COMPLETED" && event.reading != null && event.batch != null)
                    state.completedBatchKeys + "${event.reading}:${event.batch}" else state.completedBatchKeys
                val flags = event.flags.groupingBy { it.rule.name }.eachCount()
                val counts = (state.validationCounts.keys + flags.keys).associateWith {
                    state.validationCounts.getOrDefault(it, 0) + flags.getOrDefault(it, 0)
                }
                val entry = ProgressEventView(state.events.size + 1, clock.instant(), event.stage,
                    event.reading, event.batch, event.totalBatches, processed.size, flags)
                job.stateJson = mapper.writeValueAsString(state.copy(processedWishIds = processed,
                    completedBatchKeys = batches, events = state.events + entry, validationCounts = counts))
                true
            } == true
        }.getOrDefault(false)
    }

    fun finish(work: AnalysisWork, result: AnalysisResult, runId: UUID? = null, checkpointIncomplete: Boolean = false) {
        val job = entities.find(AnalysisProgressJob::class.java, work.jobId, LockModeType.PESSIMISTIC_WRITE)
        check(job != null && job.workspaceId == work.workspaceId && job.ownerId == ownerId &&
            job.status in setOf("RUNNING", "QUEUED"))
        val state = state(job).copy(checkpointIncomplete = checkpointIncomplete)
        job.runId = runId
        job.status = if (runId != null) "COMPLETED" else "FAILED"
        job.failure = (result as? AnalysisResult.Failure)?.reason?.name
        val completed = if (runId != null && result is AnalysisResult.Success) {
            state.copy(processedWishIds = state.processedWishIds + result.validated.output.wishes.map { it.wishId },
                finalCounts = optionWishCounts(result.validated.output))
        } else state
        val entry = ProgressEventView(completed.events.size + 1, clock.instant(), job.status,
            null, null, null, completed.processedWishIds.size, emptyMap())
        job.stateJson = mapper.writeValueAsString(completed.copy(events = completed.events + entry))
    }

    fun interruptIncomplete() = transaction.executeWithoutResult {
        entities.createQuery(
            "update AnalysisProgressJob j set j.status = 'INTERRUPTED', j.failure = 'PROCESS_INTERRUPTED' " +
                "where j.status in ('QUEUED', 'RUNNING') and j.ownerId <> :owner",
        ).setParameter("owner", ownerId).executeUpdate()
    }

    @Transactional(readOnly = true)
    fun latest(token: String): AnalysisProgressView? {
        val workspace = workspace(token)
        return entities.createQuery(
            "select j from AnalysisProgressJob j where j.workspaceId = :workspace order by j.createdAt desc, j.id desc",
            AnalysisProgressJob::class.java,
        ).setParameter("workspace", workspace.id).setMaxResults(1).resultList.singleOrNull()?.let(::view)
    }

    @Transactional(readOnly = true)
    fun find(token: String, id: UUID): AnalysisProgressView? {
        val workspace = workspace(token)
        return entities.find(AnalysisProgressJob::class.java, id)?.takeIf { it.workspaceId == workspace.id }?.let(::view)
    }

    fun snapshotAt(workspaceId: UUID, runId: UUID): java.time.Instant? = entities.createQuery(
        "select j.createdAt from AnalysisProgressJob j where j.workspaceId = :workspace and j.runId = :run",
        java.time.Instant::class.java,
    ).setParameter("workspace", workspaceId).setParameter("run", runId).setMaxResults(1).resultList.singleOrNull()

    fun view(job: AnalysisProgressJob): AnalysisProgressView {
        val state = state(job)
        val sources = sources(job)
        return AnalysisProgressView(job.id, job.status, sources.size, state.processedWishIds.size,
            state.completedBatchKeys.size, job.inputHash, sources, state.events, state.validationCounts,
            job.runId, state.finalCounts, job.failure, state.checkpointIncomplete)
    }

    private fun state(job: AnalysisProgressJob) = mapper.readValue(job.stateJson, ProgressState::class.java)
    private fun sources(job: AnalysisProgressJob): List<UUID> = mapper.readTree(job.sourceWishIdsJson).toList().map {
        UUID.fromString(it.asString())
    }

    private fun workspace(token: String): Workspace {
        if (!Regex("[A-Za-z0-9_-]{43}").matches(token)) throw WorkspaceNotFoundException()
        return entities.createQuery("select w from Workspace w where w.token = :token", Workspace::class.java)
            .setParameter("token", token).setMaxResults(1).resultList.singleOrNull() ?: throw WorkspaceNotFoundException()
    }
}

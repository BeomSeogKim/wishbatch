package app.campaign.progress

import app.campaign.analysisrun.AnalysisPreparation
import app.campaign.analysisrun.AnalysisRunResult
import app.campaign.analysisrun.AnalysisRunService
import jakarta.annotation.PreDestroy
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Service
class AnalysisProgressService(
    private val runs: AnalysisRunService,
    private val store: AnalysisProgressStore,
) {
    private val slots = Semaphore(4)
    private val executor = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2)) { task ->
        Thread(task, "analysis-progress").apply { isDaemon = true }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun interruptIncomplete() = store.interruptIncomplete()

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun start(token: String): ProgressStart {
        val existing = latest(token)
        if (existing?.status in setOf("QUEUED", "RUNNING")) return ProgressStart.Existing(requireNotNull(existing))
        if (!slots.tryAcquire()) return ProgressStart.Busy
        var submitted = false
        try {
            return when (val prepared = runs.prepare(token)) {
                is AnalysisPreparation.Existing -> ProgressStart.Existing(prepared.job)
                is AnalysisPreparation.Rejected -> when (val result = prepared.result) {
                    AnalysisRunResult.NoVisibleWishes -> ProgressStart.NoVisibleWishes
                    is AnalysisRunResult.Failed -> ProgressStart.Failed(result.reason)
                    is AnalysisRunResult.Completed -> error("Preparation cannot complete analysis")
                }
                is AnalysisPreparation.Ready -> {
                    try {
                        executor.execute {
                            try { runs.execute(prepared.work) } finally { slots.release() }
                        }
                        submitted = true
                        ProgressStart.Started(prepared.job)
                    } catch (_: RejectedExecutionException) {
                        runs.abandon(prepared.work)
                        ProgressStart.Busy
                    }
                }
            }
        } finally {
            if (!submitted) slots.release()
        }
    }

    fun latest(token: String): AnalysisProgressView? = store.latest(token)
    fun find(token: String, id: UUID): AnalysisProgressView? = store.find(token, id)

    @PreDestroy
    fun close() { executor.shutdownNow() }
}

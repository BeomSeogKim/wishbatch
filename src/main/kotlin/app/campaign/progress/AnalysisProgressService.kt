package app.campaign.progress

import app.campaign.analysisrun.AnalysisSubmission
import app.campaign.analysisrun.AnalysisRunResult
import app.campaign.analysisrun.AnalysisRunService
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AnalysisProgressService(
    private val runs: AnalysisRunService,
    private val store: AnalysisProgressStore,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun interruptIncomplete() = store.interruptIncomplete()

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun start(token: String): ProgressStart = when (val submitted = runs.submit(token)) {
        is AnalysisSubmission.Started -> ProgressStart.Started(submitted.job)
        is AnalysisSubmission.Existing -> ProgressStart.Existing(submitted.job)
        AnalysisSubmission.Busy -> ProgressStart.Busy
        is AnalysisSubmission.Rejected -> when (val result = submitted.result) {
            AnalysisRunResult.NoVisibleWishes -> ProgressStart.NoVisibleWishes
            is AnalysisRunResult.Failed -> ProgressStart.Failed(result.reason)
            is AnalysisRunResult.Completed -> error("Submission cannot complete analysis before scheduling")
        }
    }

    fun latest(token: String): AnalysisProgressView? = store.latest(token)
    fun find(token: String, id: UUID): AnalysisProgressView? = store.find(token, id)
}

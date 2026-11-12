package app.campaign.workbench

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.analysisrun.StoredAnalysisRun
import app.campaign.model.Option
import app.campaign.model.CompatibilityVerdict
import app.campaign.review.WishReviewService
import app.campaign.workspace.WorkspaceNotFoundException
import app.campaign.workspace.WorkspaceService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class WishWorkbench(val stored: StoredAnalysisRun?, val options: List<Option>, val rows: List<WorkbenchRow>)

@Service
@Transactional(readOnly = true)
class WishWorkbenchService(
    private val workspaces: WorkspaceService,
    private val runs: AnalysisRunService,
    private val reviews: WishReviewService,
) {
    fun read(token: String, runId: UUID?): WishWorkbench {
        val stored = if (runId == null) runs.latest(token) else runs.find(token, runId)
            ?: throw WorkspaceNotFoundException()
        val options = workspaces.listOptions(token)
        return WishWorkbench(stored, options, wishWorkbenchRows(
            workspaces.listWishes(token), options, stored?.output, stored?.flags.orEmpty(),
            stored?.overrides.orEmpty(), stored?.originalOutput,
        ))
    }

    @Transactional
    fun hide(token: String, runId: UUID, optionId: UUID, wishIds: List<UUID>) {
        selected(token, runId, optionId, wishIds).forEach { workspaces.setWishHidden(token, it, true) }
    }

    @Transactional
    fun review(
        token: String,
        runId: UUID,
        optionId: UUID,
        wishIds: List<UUID>,
        verdict: CompatibilityVerdict,
        reason: String,
    ) {
        require(verdict in setOf(CompatibilityVerdict.COMPATIBLE, CompatibilityVerdict.INCOMPATIBLE)) {
            "Choose Compatible or Not served."
        }
        val trimmed = reason.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 500) { "Give a reason of one to 500 characters." }
        selected(token, runId, optionId, wishIds).forEach {
            reviews.decide(token, runId, it, optionId, verdict, trimmed)
        }
    }

    private fun selected(token: String, runId: UUID, optionId: UUID, wishIds: List<UUID>): List<UUID> {
        val workbench = read(token, runId)
        val stored = workbench.stored ?: throw WorkspaceNotFoundException()
        require(wishIds.isNotEmpty() && wishIds.size <= 200) { "Select between one and 200 wishes." }
        require(optionId in stored.output.optionIds && workbench.options.any { it.id == optionId }) {
            "Choose an option in this analysis run."
        }
        val scopedIds = stored.originalOutput.wishes.map { it.wishId }.toSet()
            .intersect(workbench.rows.map { it.id }.toSet())
        require(wishIds.all { it in scopedIds }) { "Every selection must belong to this workspace and analysis run." }
        return wishIds.distinct()
    }
}

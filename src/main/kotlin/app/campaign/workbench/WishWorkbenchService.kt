package app.campaign.workbench

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.analysisrun.StoredAnalysisRun
import app.campaign.model.Option
import app.campaign.workspace.WorkspaceNotFoundException
import app.campaign.workspace.WorkspaceService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class WishWorkbench(val stored: StoredAnalysisRun?, val options: List<Option>, val rows: List<WorkbenchRow>)

@Service
@Transactional(readOnly = true)
class WishWorkbenchService(private val workspaces: WorkspaceService, private val runs: AnalysisRunService) {
    fun read(token: String, runId: UUID?): WishWorkbench {
        val stored = if (runId == null) runs.latest(token) else runs.find(token, runId)
            ?: throw WorkspaceNotFoundException()
        val options = workspaces.listOptions(token)
        return WishWorkbench(stored, options, wishWorkbenchRows(
            workspaces.listWishes(token), options, stored?.output, stored?.flags.orEmpty(),
        ))
    }

    @Transactional
    fun hide(token: String, runId: UUID, optionId: UUID, wishIds: List<UUID>) {
        val workbench = read(token, runId)
        val stored = workbench.stored ?: throw WorkspaceNotFoundException()
        require(wishIds.isNotEmpty() && wishIds.size <= 200) { "Select between one and 200 wishes." }
        require(optionId in stored.output.optionIds && workbench.options.any { it.id == optionId }) {
            "Choose an option in this analysis run."
        }
        val scopedIds = stored.output.wishes.map { it.wishId }.toSet()
            .intersect(workbench.rows.map { it.id }.toSet())
        require(wishIds.all { it in scopedIds }) { "Every selection must belong to this workspace and analysis run." }
        wishIds.distinct().forEach { workspaces.setWishHidden(token, it, true) }
    }
}

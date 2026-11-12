package app.campaign.gloss

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.model.Workspace
import app.campaign.provenance.DecisionSourceSnapshot
import app.campaign.workspace.WorkspaceNotFoundException
import app.campaign.workspace.WorkspaceService
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.UUID

data class GlossRow(val wishId: UUID, val original: String, val current: Boolean, val saved: WishGloss?) {
    val selectable: Boolean get() = current && saved?.status == null && (saved?.attempts ?: 0) < 2
}

data class GlossPage(val runId: UUID, val rows: List<GlossRow>)

@Service
@Transactional(readOnly = true)
class WishGlossService(
    private val entities: EntityManager,
    private val workspaces: WorkspaceService,
    private val runs: AnalysisRunService,
    private val mapper: JsonMapper,
    private val translators: ObjectProvider<WishGlossTranslator>,
    private val environment: Environment,
    private val clock: Clock,
) {
    fun read(token: String, runId: UUID): GlossPage {
        val stored = runs.find(token, runId) ?: throw WorkspaceNotFoundException()
        val workspaceId = stored.run.workspaceId
        val roster = stored.originalOutput.wishes.map { it.wishId }.toSet()
        val snapshot = entities.createQuery(
            "select s from DecisionSourceSnapshot s where s.workspaceId = :workspace and s.sourceType = 'ANALYSIS' and s.sourceId = :run",
            DecisionSourceSnapshot::class.java,
        ).setParameter("workspace", workspaceId).setParameter("run", runId).resultList.singleOrNull()
        val originals = if (snapshot != null && glossSourceHash(snapshot.payload) == snapshot.hash) {
            mapper.readTree(snapshot.payload).path("wishes").associate {
                UUID.fromString(it.get("wishId").asString()) to it.get("text").asString()
            }
        } else emptyMap()
        val saved = entities.createQuery(
            "select g from WishGloss g where g.workspaceId = :workspace", WishGloss::class.java,
        ).setParameter("workspace", workspaceId).resultList.associateBy { it.wishId to it.sourceHash }
        return GlossPage(runId, workspaces.listWishes(token, includeHidden = false).filter { it.id in roster }.map { wish ->
            val current = originals[wish.id] == wish.text
            GlossRow(wish.id, wish.text, current, if (current) saved[wish.id to glossSourceHash(wish.text)] else null)
        })
    }

    @Transactional
    fun generate(token: String, runId: UUID, wishIds: List<UUID>): GlossResult {
        require(wishIds.size in 1..20 && wishIds.distinct().size == wishIds.size)
        val workspace = workspaces.findByToken(token) ?: throw WorkspaceNotFoundException()
        entities.find(Workspace::class.java, workspace.id, LockModeType.PESSIMISTIC_WRITE)
        val page = read(token, runId)
        val rows = page.rows.associateBy { it.wishId }
        require(wishIds.all { rows[it]?.current == true })
        val selected = wishIds.map { rows.getValue(it) }
        if (selected.any { !it.selectable }) return GlossResult.Failed(GlossFailure.LIMIT_REACHED)
        if (environment.getProperty("LLM_DAILY_RUN_CAP", Int::class.java, 100) <= 0) {
            return GlossResult.Failed(GlossFailure.LIMIT_REACHED)
        }
        val translator = translators.ifAvailable ?: return GlossResult.Failed(GlossFailure.NOT_CONFIGURED)
        val request = GlossRequest(selected.map { GlossSource(it.wishId, glossSourceHash(it.original), it.original) })
        val records = selected.map { row ->
            (row.saved ?: WishGloss(workspace.id, row.wishId, glossSourceHash(row.original)).also(entities::persist))
                .also { it.attempts++ }
        }
        val result = try {
            translator.translate(request)
        } catch (_: Exception) {
            GlossResult.Failed(GlossFailure.PROVIDER_FAILURE)
        }
        if (result is GlossResult.Completed) {
            try {
                require(result.model.isNotBlank() && result.model.length <= 120)
                validateGlosses(request.sources, result.translations)
            } catch (_: IllegalArgumentException) {
                return GlossResult.Failed(GlossFailure.INVALID_OUTPUT)
            }
            val byId = result.translations.associateBy { it.wishId }
            records.forEach { record ->
                val translation = byId.getValue(record.wishId)
                record.status = translation.status
                record.inferredLanguage = translation.inferredLanguage
                record.englishGloss = translation.englishGloss
                record.model = result.model
                record.generatedAt = clock.instant()
            }
        }
        return result
    }
}

package app.campaign.explanation

import app.campaign.port.AnalysisOutput
import app.campaign.rules.OptionWishCounts
import app.campaign.rules.optionWishCounts
import app.campaign.store.AnalysisRunRepository
import app.campaign.store.WishRepository
import app.campaign.workspace.SeedCatalog
import app.campaign.workspace.SeedWish
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

data class BatchEvidence(val counts: OptionWishCounts, val quotes: List<String>, val isSeed: Boolean)

@Service
@Transactional(readOnly = true)
class BatchExplanationQuery(
    private val runs: AnalysisRunRepository,
    private val wishes: WishRepository,
    private val seed: SeedCatalog,
    private val mapper: JsonMapper,
) {
    private val scenarioWishes: List<SeedWish> = PathMatchingResourcePatternResolver()
        .getResources("classpath*:seed/*/wishes.json").flatMap { resource ->
            resource.inputStream.use { mapper.readValue(it, Array<SeedWish>::class.java).toList() }
        }

    fun evidence(workspaceId: UUID, runId: UUID, optionId: UUID): BatchEvidence? {
        val run = runs.findByIdAndWorkspaceId(runId, workspaceId) ?: return null
        val output = mapper.readValue(run.resultJson, AnalysisOutput::class.java)
        val counts = optionWishCounts(output).singleOrNull { it.optionId == optionId } ?: return null
        val proposal = output.proposal?.takeIf { it.optionId == optionId }
        val seedTexts = (seed.wishes + scenarioWishes).groupBy({ seed.wishId(workspaceId, it.key) }, { it.text })
        val visible = wishes.findByWorkspaceId(workspaceId).filterNot { it.hidden }.associateBy { it.id }
        val quotes = proposal?.rationale.orEmpty().mapNotNull { item ->
            val wish = visible[item.wishId] ?: return@mapNotNull null
            val originals = seedTexts[item.wishId] ?: return@mapNotNull null
            if (wish.text !in originals || item.quote.isBlank() || !wish.text.contains(item.quote)) return@mapNotNull null
            item.quote
        }.distinct().take(3)
        return BatchEvidence(counts, quotes, run.isSeed)
    }
}

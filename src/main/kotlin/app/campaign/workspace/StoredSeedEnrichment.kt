package app.campaign.workspace

import app.campaign.analysisrun.SeedAnalysisFixture
import app.campaign.analysisrun.StoredAnalysisRun
import app.campaign.analysisrun.StoredSeedAnalysis
import app.campaign.followup.FollowupDraft
import app.campaign.followup.FollowupDraftAttempt
import app.campaign.followup.FollowupQuestion
import app.campaign.followup.FollowupQuestions
import app.campaign.gloss.GlossSource
import app.campaign.gloss.GlossFailure
import app.campaign.gloss.GlossTranslation
import app.campaign.gloss.WishGloss
import app.campaign.gloss.validateGlosses
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.Workspace
import app.campaign.port.AnalysisFailure
import app.campaign.replies.ReplyBatch
import app.campaign.replies.ReplySafety
import app.campaign.replies.ReplyService
import app.campaign.rules.analysisInputHash
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

data class SeedEnrichment(
    val inputHash: String,
    val generatedAt: Instant,
    val followup: SeedFollowup,
    val replies: SeedReplies,
    val gloss: SeedGloss,
)

data class SeedFollowup(
    val drafts: List<FollowupDraft>,
    val model: String?,
    val failure: String?,
    val completedAt: Instant,
)

data class SeedReplies(
    val bodies: List<String>,
    val model: String?,
    val failure: String?,
    val completedAt: Instant,
)

data class SeedGloss(
    val sources: List<GlossSource>,
    val translations: List<GlossTranslation>,
    val model: String?,
    val failure: String?,
    val completedAt: Instant,
    val generatedAtByWishId: Map<UUID, Instant> = emptyMap(),
)

@Component
class StoredSeedEnrichment(
    private val entities: EntityManager,
    private val catalog: SeedCatalog,
    private val mapper: JsonMapper,
    private val replies: ObjectProvider<ReplyService>,
) {
    fun attach(workspace: Workspace, scenario: SeedScenario, stored: StoredAnalysisRun, options: List<Option>, wishes: List<Wish>) {
        val path = if (scenario.key == "illustrator") "seed" else "seed/${scenario.key}"
        val resource = ClassPathResource("$path/enrichment.json")
        if (!resource.exists()) return
        val fixture = ClassPathResource("$path/analysis-run.json").inputStream.use {
            mapper.readValue(it, SeedAnalysisFixture::class.java)
        }
        val artifact = resource.inputStream.use {
            mapper.readerFor(SeedEnrichment::class.java)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readValue<SeedEnrichment>(it)
        }
        require(stored.run.isSeed && stored.run.workspaceId == workspace.id)
        require(artifact.inputHash == fixture.inputHash) { "Stored enrichment does not match the seed analysis" }
        val expected = StoredSeedAnalysis(catalog, mapper).forWorkspace(workspace, options, wishes)
        require(stored.run.inputHash == analysisInputHash(options, wishes) && stored.run.inputHash == expected.inputHash)
        require(stored.originalOutput == expected.validated.output) { "Stored enrichment requires the original seed output" }
        val times = listOf(artifact.followup.completedAt, artifact.replies.completedAt, artifact.gloss.completedAt)
        require(times.all { !it.isAfter(artifact.generatedAt) })
        val wishIds = fixture.wishKeys.mapValues { (_, key) -> catalog.wishId(workspace.id, key) }
        val localWishes = wishes.associateBy { it.id }
        val output = stored.originalOutput
        val option = options.singleOrNull { it.id == output.proposal?.optionId }
        artifact.followup.failure?.let(AnalysisFailure::valueOf)
        artifact.gloss.failure?.let(GlossFailure::valueOf)
        val drafts = artifact.followup.drafts.map { draft ->
            draft.copy(wishIds = draft.wishIds.map(wishIds::getValue))
        }
        require(artifact.followup.failure == null || drafts.isEmpty() && artifact.followup.model == null)
        if (artifact.followup.failure == null) {
            require(!artifact.followup.model.isNullOrBlank())
            if (option == null) require(drafts.isEmpty()) else FollowupQuestions.validate(drafts, option, output)
        }
        val replyView = replies.getObject().maker(workspace.token, stored.run.id)
        val groups = replyView.groups.map { it.evidence }
        val reply = artifact.replies
        require(reply.failure == null || reply.bodies.isEmpty() && reply.model == null)
        require(reply.failure == null || reply.failure.isNotBlank() && reply.failure !in setOf("DRAFTED", "READY", "EMPTY", "GENERATING"))
        if (reply.failure == null) {
            require(!reply.model.isNullOrBlank() && reply.bodies.size == groups.size)
            require(groups.zip(reply.bodies).all { (group, body) -> ReplySafety.validateBody(body, group.reasons) })
        }
        val gloss = artifact.gloss
        val sources = gloss.sources.map { source ->
            val localId = wishIds.getValue(source.wishId)
            require(localWishes.getValue(localId).text == source.text) { "Stored reading aid source has changed" }
            source.copy(wishId = localId)
        }
        require(sources.size <= 20 && sources.map { it.wishId }.distinct().size == sources.size)
        val translations = gloss.translations.map { it.copy(wishId = wishIds.getValue(it.wishId)) }
        require(gloss.generatedAtByWishId.keys.all { id -> gloss.sources.any { it.wishId == id } })
        require(gloss.generatedAtByWishId.values.all { !it.isAfter(artifact.generatedAt) })
        val generatedAt = gloss.generatedAtByWishId.mapKeys { wishIds.getValue(it.key) }
        require(gloss.failure == null || translations.isEmpty() && gloss.model == null)
        if (gloss.failure == null) {
            require(!gloss.model.isNullOrBlank() && gloss.model.length <= 120)
            validateGlosses(sources, translations)
        }
        require(sources.all { it.sourceHash == app.campaign.gloss.glossSourceHash(it.text) })

        entities.persist(FollowupDraftAttempt(stored.run.id, workspace.id, artifact.followup.completedAt,
            artifact.followup.failure, artifact.followup.model))
        drafts.forEach { draft ->
            entities.persist(FollowupQuestion(workspace.id, stored.run.id, requireNotNull(option).id, draft.attribute,
                draft.variantKey, draft.text, draft.choices.toList(), draft.wishIds.toList(), artifact.followup.completedAt))
        }
        entities.persist(ReplyBatch(stored.run.id, workspace.id,
            replyView.evidenceHash, reply.failure ?: "DRAFTED",
            mapper.writeValueAsString(groups.mapIndexed { index, group -> group.groupId to reply.bodies.getOrNull(index) }
                .filter { it.second != null }.toMap()), reply.model))
        val byId = translations.associateBy { it.wishId }
        sources.forEach { source ->
            val translated = byId[source.wishId]
            entities.persist(WishGloss(workspace.id, source.wishId, source.sourceHash, attempts = 1,
                status = translated?.status, inferredLanguage = translated?.inferredLanguage,
                englishGloss = translated?.englishGloss, model = gloss.model,
                generatedAt = if (translated == null) null else generatedAt[source.wishId] ?: gloss.completedAt))
        }
    }
}

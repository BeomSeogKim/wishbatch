package app.campaign.offerdraft

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.model.CampaignState
import app.campaign.model.Option
import app.campaign.model.Workspace
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.WishKind
import app.campaign.rules.analysisInputHash
import app.campaign.workspace.WorkspaceNotFoundException
import app.campaign.workspace.WorkspaceService
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.Optional
import java.util.UUID

class InvalidOfferDraftException : RuntimeException()

data class OfferDraftPage(
    val runId: UUID,
    val option: Option,
    val sourceHash: String,
    val evidence: List<OfferDraftEvidence>,
    val draft: OfferTextDraft?,
    val removals: List<OfferDraftRemoval>,
    val canGenerate: Boolean,
    val published: Boolean,
    val generationFailed: Boolean,
)

@Service
@Transactional(readOnly = true)
class OfferDraftService(
    private val entityManager: EntityManager,
    private val workspaces: WorkspaceService,
    private val runs: AnalysisRunService,
    private val mapper: JsonMapper,
    private val clock: Clock,
    private val generator: Optional<OfferDraftGenerator>,
    @Value("\${LLM_DAILY_RUN_CAP:100}") private val dailyCap: Int,
) {
    private data class Source(val workspace: Workspace, val option: Option, val input: OfferDraftInput)

    fun page(token: String, runId: UUID): OfferDraftPage {
        val source = source(token, runId)
        val attempts = attempts(source.workspace.id, runId)
        val current = attempts.filter { it.sourceHash == source.input.sourceHash }
        val latest = current.lastOrNull()
        val draft = current.lastOrNull { it.failure == null } ?: latest
        val published = published(source.workspace.id, runId)
        return OfferDraftPage(runId, source.option, source.input.sourceHash, source.input.evidence, draft,
            latest?.let { mapper.readValue(it.removalsJson, Array<OfferDraftRemoval>::class.java).toList() }.orEmpty(),
            generator.isPresent && dailyCap > 0 && attempts.size < 2 && !published, published, latest?.failure != null)
    }

    @Transactional
    fun generate(token: String, runId: UUID, sourceHash: String): OfferDraftGeneration {
        lock(token)
        val source = source(token, runId)
        requireCurrent(source, sourceHash)
        if (published(source.workspace.id, runId)) throw InvalidOfferDraftException()
        if (dailyCap <= 0) return OfferDraftGeneration.Unavailable("CALL_LIMIT")
        val provider = generator.orElse(null) ?: return OfferDraftGeneration.Unavailable("NOT_CONFIGURED")
        val attemptNumber = attempts(source.workspace.id, runId).size + 1
        if (attemptNumber > 2) return OfferDraftGeneration.Unavailable("ATTEMPT_LIMIT")
        val result = try {
            provider.generate(source.input, Duration.ofSeconds(15), 1536)
        } catch (_: RuntimeException) {
            OfferDraftGeneration.Unavailable("PROVIDER_FAILURE")
        }
        // Re-read effective evidence after the request; a changed review cannot authorize old copy.
        val current = source(token, runId)
        val validation = if (result is OfferDraftGeneration.Generated && current.input.sourceHash == sourceHash) {
            OfferDraftValidator(mapper).validate(result.json, source.input.facts)
        } else null
        val failure = when {
            current.input.sourceHash != sourceHash -> "STALE_SOURCE"
            result is OfferDraftGeneration.Unavailable -> result.reason
            result is OfferDraftGeneration.Generated && result.model.isBlank() -> "INVALID_OUTPUT"
            validation?.available != true -> "INVALID_OUTPUT"
            else -> null
        }
        entityManager.persist(OfferTextDraft(source.workspace.id, runId, source.option.id, attemptNumber, sourceHash,
            mapper.writeValueAsString(source.input.evidence), clock.instant(),
            generatedTitle = validation?.title.takeIf { failure == null },
            generatedDescription = validation?.description.takeIf { failure == null },
            removalsJson = mapper.writeValueAsString(validation?.removals.orEmpty()), failure = failure,
            model = (result as? OfferDraftGeneration.Generated)?.model?.take(255)))
        return if (failure == null) result else OfferDraftGeneration.Unavailable(failure)
    }

    @Transactional
    fun save(token: String, runId: UUID, draftId: UUID, sourceHash: String, title: String, description: String) {
        lock(token)
        val source = source(token, runId)
        val draft = currentDraft(source, runId, draftId, sourceHash)
        if (title.isBlank() || title.length > 120 || description.isBlank() || description.length > 1000) {
            throw InvalidOfferDraftException()
        }
        draft.reviewedTitle = title
        draft.reviewedDescription = description
        draft.reviewedAt = clock.instant()
    }

    /** Only these two plain-text fields may be copied into the editable offer form. */
    fun reviewedCopy(token: String, runId: UUID, draftId: UUID, sourceHash: String): Map<String, String> {
        val draft = currentDraft(source(token, runId), runId, draftId, sourceHash)
        if (draft.reviewedAt == null) throw InvalidOfferDraftException()
        return mapOf("title" to draft.reviewedTitle!!, "specText" to draft.reviewedDescription!!)
    }

    private fun currentDraft(source: Source, runId: UUID, id: UUID, hash: String): OfferTextDraft {
        requireCurrent(source, hash)
        if (published(source.workspace.id, runId)) throw InvalidOfferDraftException()
        return entityManager.find(OfferTextDraft::class.java, id)?.takeIf {
            it.workspaceId == source.workspace.id && it.runId == runId && it.optionId == source.option.id &&
                it.sourceHash == hash && it.failure == null && it.generatedTitle != null && it.generatedDescription != null
        } ?: throw InvalidOfferDraftException()
    }

    private fun source(token: String, runId: UUID): Source {
        val workspace = workspaces.findByToken(token) ?: throw WorkspaceNotFoundException()
        val stored = runs.find(token, runId) ?: throw WorkspaceNotFoundException()
        val options = workspaces.listOptions(token)
        val wishes = workspaces.listWishes(token, includeHidden = false)
        if (analysisInputHash(options, wishes) != stored.run.inputHash) throw InvalidOfferDraftException()
        val proposal = stored.output.proposal ?: throw InvalidOfferDraftException()
        if (proposal.optionId !in stored.output.optionIds) throw InvalidOfferDraftException()
        val option = options.singleOrNull { it.id == proposal.optionId } ?: throw InvalidOfferDraftException()
        if (proposal.suggestedVariants.any { (key, value) -> value !in option.allowedVariants[key].orEmpty() }) {
            throw InvalidOfferDraftException()
        }
        val evidence = proposal.rationale.map { item ->
            val wish = wishes.singleOrNull { it.id == item.wishId } ?: throw InvalidOfferDraftException()
            if (item.quote.isBlank() || !normalized(wish.text).contains(normalized(item.quote)) ||
                stored.output.wishes.none { it.wishId == wish.id && it.kind == WishKind.REQUEST } ||
                stored.output.verdicts.none { it.wishId == wish.id && it.optionId == option.id &&
                    it.verdict in setOf(CompatibilityVerdict.COMPATIBLE, CompatibilityVerdict.CONDITIONAL) }) {
                throw InvalidOfferDraftException()
            }
            OfferDraftEvidence(wish.id, item.quote)
        }.distinct().take(10)
        if (evidence.isEmpty()) throw InvalidOfferDraftException()
        val facts = listOf(option.name, option.format) + option.fixedAttributes.toSortedMap().values +
            proposal.suggestedVariants.toSortedMap().values
        if (facts.size > 30 || facts.any { it.length > 500 } || evidence.any { it.quote.length > 500 }) {
            throw InvalidOfferDraftException()
        }
        val canonical = mapper.writeValueAsString(listOf(stored.run.inputHash, stored.output,
            stored.overrides.sortedBy { it.id }.map { listOf(it.id, it.revision, it.verdict, it.reason, it.source, it.answerId) }))
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val input = OfferDraftInput(facts, evidence, hash)
        if (mapper.writeValueAsString(input).length > 16000) throw InvalidOfferDraftException()
        return Source(workspace, option, input)
    }

    private fun normalized(value: String) = value.replace(Regex("[\\s\\p{Z}]+"), " ").trim()

    private fun requireCurrent(source: Source, hash: String) {
        if (source.input.sourceHash != hash) throw InvalidOfferDraftException()
    }

    private fun attempts(workspaceId: UUID, runId: UUID): List<OfferTextDraft> = entityManager.createQuery(
        "select d from OfferTextDraft d where d.workspaceId = :workspace and d.runId = :run order by d.attemptNumber",
        OfferTextDraft::class.java,
    ).setParameter("workspace", workspaceId).setParameter("run", runId).resultList

    private fun published(workspaceId: UUID, runId: UUID): Boolean = entityManager.createQuery(
        "select count(c) from Campaign c, Offer o where c.offerId = o.id and o.workspaceId = :workspace " +
            "and o.analysisRunId = :run and c.state <> :draft", Long::class.javaObjectType,
    ).setParameter("workspace", workspaceId).setParameter("run", runId)
        .setParameter("draft", CampaignState.DRAFT).singleResult > 0

    private fun lock(token: String) {
        val workspace = workspaces.findByToken(token) ?: throw WorkspaceNotFoundException()
        entityManager.lock(workspace, LockModeType.PESSIMISTIC_WRITE)
    }
}

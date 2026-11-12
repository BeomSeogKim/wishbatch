package app.campaign.replies

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.WishKind
import app.campaign.model.Workspace
import app.campaign.rules.analysisInputHash
import app.campaign.workspace.WorkspaceNotFoundException
import app.campaign.workspace.WorkspaceService
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

open class InvalidReplyEvidenceException : RuntimeException()
private class StaleReplyEvidenceException : InvalidReplyEvidenceException()
class UnsafeReplyException : RuntimeException()

data class MakerReplyGroup(val evidence: ReplyEvidenceGroup, val draft: String?, val approved: Boolean)
data class MakerReplies(val runId: UUID, val evidenceHash: String, val status: String, val groups: List<MakerReplyGroup>)
data class ApprovedWishReply(val body: String, val quote: String, val approvedAt: Instant)

@Service
class ReplyService(
    private val entityManager: EntityManager,
    private val workspaces: WorkspaceService,
    private val runs: AnalysisRunService,
    private val drafter: ReplyDrafter,
    private val mapper: JsonMapper,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val readTransaction = TransactionTemplate(transactionManager)
    private val transaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    fun maker(token: String, runId: UUID): MakerReplies = within(readTransaction) {
        lock(token)
        try {
            view(snapshot(token, runId))
        } catch (_: StaleReplyEvidenceException) {
            val latestId = runs.latest(token)?.run?.id ?: throw WorkspaceNotFoundException()
            val latest = try { snapshot(token, latestId) } catch (_: StaleReplyEvidenceException) { null }
            MakerReplies(latestId, latest?.hash.orEmpty(),
                if (latest == null) "REANALYSIS_REQUIRED" else "REFRESH_REQUIRED", emptyList())
        }
    }

    fun generate(token: String, runId: UUID, evidenceHash: String): MakerReplies {
        val reserved = within {
            lock(token)
            val snapshot = snapshot(token, runId)
            if (snapshot.hash != evidenceHash) throw InvalidReplyEvidenceException()
            val batch = entityManager.find(ReplyBatch::class.java, runId)
            if (snapshot.groups.isEmpty() || (batch?.evidenceHash == snapshot.hash && batch.status != "STALE")) null
            else {
                if (batch == null) entityManager.persist(ReplyBatch(runId, snapshot.workspaceId, snapshot.hash))
                else {
                    batch.evidenceHash = snapshot.hash
                    batch.status = "GENERATING"
                    batch.draftsJson = "{}"
                    batch.model = null
                    entityManager.createQuery("delete from ReplyApproval a where a.workspaceId = :workspace and a.runId = :run")
                        .setParameter("workspace", snapshot.workspaceId).setParameter("run", runId).executeUpdate()
                }
                snapshot
            }
        }
        if (reserved != null) {
            // Commit the reservation before calling the provider: a failed request never retries it.
            val result = try {
                drafter.draft(reserved.groups)
            } catch (_: RuntimeException) {
                ReplyDraftResult.Failure("GENERATION_FAILED")
            }
            within {
                lock(token)
                val batch = entityManager.find(ReplyBatch::class.java, runId)
                // A newer explicit refresh owns this batch; an older provider result must not overwrite it.
                if (batch.evidenceHash != reserved.hash) return@within
                val current = runCatching { snapshot(token, runId) }.getOrNull()
                val success = result as? ReplyDraftResult.Success
                if (current == null || current.hash != reserved.hash) batch.status = "STALE"
                else if (success == null) batch.status = (result as ReplyDraftResult.Failure).code
                else if (success.bodies.keys != reserved.groups.map { it.groupId }.toSet() ||
                    reserved.groups.any { !ReplySafety.validateBody(success.bodies.getValue(it.groupId), it.reasons) }
                ) batch.status = "INVALID_OUTPUT"
                else {
                    batch.draftsJson = mapper.writeValueAsString(success.bodies)
                    batch.model = success.model
                    batch.status = "DRAFTED"
                }
            }
        }
        return maker(token, runId)
    }

    fun approve(token: String, runId: UUID, groupId: String, evidenceHash: String, body: String) = within {
        lock(token)
        val snapshot = snapshot(token, runId)
        if (snapshot.hash != evidenceHash) throw InvalidReplyEvidenceException()
        val group = snapshot.groups.singleOrNull { it.groupId == groupId } ?: throw InvalidReplyEvidenceException()
        val batch = entityManager.find(ReplyBatch::class.java, runId)
        if (batch == null || batch.evidenceHash != snapshot.hash || batch.status != "DRAFTED") {
            throw InvalidReplyEvidenceException()
        }
        val trimmed = body.trim()
        if (!ReplySafety.validateBody(trimmed, group.reasons)) throw UnsafeReplyException()
        val id = approvalId(runId, groupId)
        val approval = entityManager.find(ReplyApproval::class.java, id)
        if (approval == null) entityManager.persist(ReplyApproval(
            id, snapshot.workspaceId, runId, groupId, snapshot.hash, trimmed, clock.instant(),
        ))
        else if (approval.evidenceHash != snapshot.hash) throw InvalidReplyEvidenceException()
        else if (approval.body != trimmed) {
            approval.body = trimmed
            approval.approvedAt = clock.instant()
        }
    }

    /** Returns only this wish's approved body and its own source quote, never group drafts or other fans. */
    fun approvedForWish(token: String, runId: UUID, wishId: UUID): ApprovedWishReply? = within(readTransaction) {
        lock(token)
        val snapshot = try {
            snapshot(token, runId)
        } catch (_: InvalidReplyEvidenceException) {
            return@within null
        }
        val group = snapshot.groups.singleOrNull { it.sources.any { source -> source.wishId == wishId } }
            ?: return@within null
        val approval = entityManager.find(ReplyApproval::class.java, approvalId(runId, group.groupId))
            ?.takeIf { it.workspaceId == snapshot.workspaceId && it.evidenceHash == snapshot.hash }
            ?: return@within null
        if (!ReplySafety.validateBody(approval.body, group.reasons)) return@within null
        ApprovedWishReply(approval.body, group.sources.single { it.wishId == wishId }.quote, approval.approvedAt)
    }

    fun latestApprovedForWish(token: String, wishId: UUID): ApprovedWishReply? {
        val runId = runs.latest(token)?.run?.id ?: return null
        return approvedForWish(token, runId, wishId)
    }

    private data class Snapshot(val workspaceId: UUID, val runId: UUID, val hash: String, val groups: List<ReplyEvidenceGroup>)

    private fun snapshot(token: String, runId: UUID): Snapshot {
        val workspace = workspaces.findByToken(token) ?: throw WorkspaceNotFoundException()
        val stored = runs.find(token, runId) ?: throw WorkspaceNotFoundException()
        if (runs.latest(token)?.run?.id != runId) throw StaleReplyEvidenceException()
        val wishes = workspaces.listWishes(token, includeHidden = false)
        val options = workspaces.listOptions(token)
        if (analysisInputHash(options, wishes) != stored.run.inputHash) throw StaleReplyEvidenceException()
        val output = stored.output
        val proposal = output.proposal
        val sources = wishes.associateBy { it.id }
        val seen = mutableSetOf<UUID>()
        val groups = output.notServed.map { group ->
            if (proposal == null || group.wishIds.isEmpty() || group.wishIds.any { id ->
                    !seen.add(id) || sources[id] == null ||
                        output.wishes.singleOrNull { it.wishId == id }?.kind != WishKind.REQUEST ||
                        output.verdicts.singleOrNull { it.wishId == id && it.optionId == proposal.optionId }
                            ?.let { it.verdict == CompatibilityVerdict.INCOMPATIBLE && it.reason == group.reason } != true
                }
            ) throw InvalidReplyEvidenceException()
            val unchanged = group.wishIds.all { id ->
                stored.originalOutput.verdicts.singleOrNull { it.wishId == id && it.optionId == proposal.optionId }
                    ?.let { it.verdict == CompatibilityVerdict.INCOMPATIBLE && it.reason == group.reason } == true &&
                    stored.overrides.none { it.wishId == id && it.optionId == proposal.optionId }
            }
            ReplyEvidenceGroup(
                hash(mapper.writeValueAsString(listOf(runId.toString(), group.reason, group.wishIds.sorted().map(UUID::toString)))),
                group.wishIds.sorted().map { ReplySource(it, sources.getValue(it).text) },
                listOf(if (!ReplySafety.isSafeReason(group.reason) && unchanged)
                    ReplySafety.notServedExplanation else group.reason),
            )
        }
        val expected = output.verdicts.filter {
            it.optionId == proposal?.optionId && it.verdict == CompatibilityVerdict.INCOMPATIBLE &&
                output.wishes.singleOrNull { wish -> wish.wishId == it.wishId }?.kind == WishKind.REQUEST
        }.map { it.wishId }.toSet()
        if (seen != expected) throw InvalidReplyEvidenceException()
        val fingerprint = hash(mapper.writeValueAsString(listOf(stored.run.inputHash, output, stored.overrides, groups)))
        return Snapshot(workspace.id, runId, fingerprint, groups)
    }

    private fun view(snapshot: Snapshot): MakerReplies {
        val batch = entityManager.find(ReplyBatch::class.java, snapshot.runId)
        val valid = batch?.takeIf { it.workspaceId == snapshot.workspaceId && it.evidenceHash == snapshot.hash }
        val drafts = valid?.takeIf { it.status == "DRAFTED" }?.let { mapper.readTree(it.draftsJson) }
        return MakerReplies(snapshot.runId, snapshot.hash, if (snapshot.groups.isEmpty()) "EMPTY"
            else valid?.status?.takeUnless { it == "STALE" } ?: if (batch == null) "READY" else "REFRESH_REQUIRED", snapshot.groups.map { group ->
            val approved = entityManager.find(ReplyApproval::class.java, approvalId(snapshot.runId, group.groupId))
                ?.takeIf { it.workspaceId == snapshot.workspaceId && it.evidenceHash == snapshot.hash }
            MakerReplyGroup(group, approved?.body ?: drafts?.path(group.groupId)?.asString(), approved != null)
        })
    }

    private fun lock(token: String) {
        entityManager.createQuery("select w from Workspace w where w.token = :token", Workspace::class.java)
            .setParameter("token", token).setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .resultList.singleOrNull() ?: throw WorkspaceNotFoundException()
    }

    private fun approvalId(runId: UUID, groupId: String): UUID = UUID.nameUUIDFromBytes("$runId:$groupId".toByteArray())
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private data class TransactionValue<T>(val value: T)
    private fun <T> within(template: TransactionTemplate = transaction, action: () -> T): T =
        template.execute { TransactionValue(action()) }!!.value
}

package app.campaign.workspace

import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishSource
import app.campaign.model.Workspace
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID

class WorkspaceNotFoundException : NoSuchElementException("Workspace not found")
class WishNotFoundException : NoSuchElementException("Wish not found")
class WishLimitReachedException : IllegalStateException("Workspace already contains 200 wishes")

data class ImportedWishResult(val added: Int, val tooLong: Int, val overLimit: Int, val overPasteLimit: Int) {
    val skipped: Int get() = tooLong + overLimit + overPasteLimit
}

@Service
@Transactional(readOnly = true)
class WorkspaceService(
    private val entityManager: EntityManager,
    private val seedCatalog: SeedCatalog,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val random = SecureRandom()
    private val tokenPattern = Regex("[A-Za-z0-9_-]{43}")

    @Transactional
    fun create(): Workspace {
        val bytes = ByteArray(32).also(random::nextBytes)
        val workspace = Workspace(
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
            createdAt = clock.instant(),
        )
        entityManager.persist(workspace)
        for (seed in seedCatalog.definition.options) {
            entityManager.persist(Option(
                workspaceId = workspace.id,
                name = seed.name,
                format = seed.format,
                fixedAttributes = seed.fixedAttributes,
                allowedVariants = seed.allowedVariants,
                unitCost = seed.unitCost,
                currency = seed.currency,
                minBatch = seed.minBatch,
                maxCapacity = seed.maxCapacity,
                leadTimeText = seed.leadTimeText,
                shipRegion = seed.shipRegion,
                id = seedCatalog.optionId(workspace.id, seed.key),
            ))
        }
        for (seed in seedCatalog.wishes) {
            entityManager.persist(Wish(
                workspaceId = workspace.id,
                text = seed.text,
                source = WishSource.IMPORTED,
                displayName = seed.displayName,
                createdAt = workspace.createdAt,
                id = seedCatalog.wishId(workspace.id, seed.key),
            ))
        }
        return workspace
    }

    fun findByToken(token: String): Workspace? = lookup(token)

    fun listOptions(token: String): List<Option> {
        val workspace = lookup(token) ?: throw WorkspaceNotFoundException()
        return entityManager.createQuery(
            "select o from Option o where o.workspaceId = :workspaceId order by o.name", Option::class.java,
        ).setParameter("workspaceId", workspace.id).resultList
    }

    /** Hidden wishes remain visible to the maker; analysis callers pass includeHidden = false. */
    fun listWishes(token: String, includeHidden: Boolean = true): List<Wish> {
        val workspace = lookup(token) ?: throw WorkspaceNotFoundException()
        return entityManager.createQuery(
            "select w from Wish w where w.workspaceId = :workspaceId " +
                "and (:includeHidden = true or w.hidden = false) order by w.createdAt, w.id", Wish::class.java,
        ).setParameter("workspaceId", workspace.id).setParameter("includeHidden", includeHidden).resultList
    }

    @Transactional
    fun addWish(token: String, text: String, displayName: String? = null): Wish {
        val trimmedText = text.trim()
        val trimmedName = displayName?.trim()?.takeIf { it.isNotEmpty() }
        require(trimmedText.isNotEmpty() && trimmedText.length <= 500) { "Wish must contain 1 to 500 characters" }
        require(trimmedName == null || trimmedName.length <= 255) { "Display name must not exceed 255 characters" }
        // Serialize intake on the workspace so concurrent submissions cannot both take the last slot.
        val workspace = lookup(token, lock = true) ?: throw WorkspaceNotFoundException()
        val count = entityManager.createQuery(
            "select count(w) from Wish w where w.workspaceId = :workspaceId", Long::class.javaObjectType,
        ).setParameter("workspaceId", workspace.id).singleResult
        if (count >= 200) throw WishLimitReachedException()
        return Wish(
            workspaceId = workspace.id,
            text = trimmedText,
            source = WishSource.ONSITE,
            displayName = trimmedName,
            createdAt = clock.instant(),
        ).also(entityManager::persist)
    }

    @Transactional
    fun addImportedWishes(token: String, text: String): ImportedWishResult {
        val workspace = lookup(token, lock = true) ?: throw WorkspaceNotFoundException()
        val count = entityManager.createQuery(
            "select count(w) from Wish w where w.workspaceId = :workspaceId", Long::class.javaObjectType,
        ).setParameter("workspaceId", workspace.id).singleResult
        var added = 0
        var tooLong = 0
        var overLimit = 0
        var overPasteLimit = 0
        text.lineSequence().forEachIndexed { index, line ->
            val wish = line.trim()
            if (wish.isNotEmpty()) {
                when {
                    index >= 100 -> overPasteLimit++
                    wish.length > 500 -> tooLong++
                    count + added >= 200 -> overLimit++
                    else -> {
                        entityManager.persist(Wish(
                            workspaceId = workspace.id,
                            text = wish,
                            source = WishSource.IMPORTED,
                            createdAt = clock.instant(),
                        ))
                        added++
                    }
                }
            }
        }
        return ImportedWishResult(added, tooLong, overLimit, overPasteLimit)
    }

    @Transactional
    fun setWishHidden(token: String, wishId: UUID, hidden: Boolean): Wish {
        val workspace = lookup(token) ?: throw WorkspaceNotFoundException()
        val wish = entityManager.createQuery(
            "select w from Wish w where w.workspaceId = :workspaceId and w.id = :wishId", Wish::class.java,
        ).setParameter("workspaceId", workspace.id).setParameter("wishId", wishId)
            .resultList.singleOrNull() ?: throw WishNotFoundException()
        wish.hidden = hidden
        return wish
    }

    private fun lookup(token: String, lock: Boolean = false): Workspace? {
        if (!tokenPattern.matches(token)) return null
        val query = entityManager.createQuery(
            "select w from Workspace w where w.token = :token", Workspace::class.java,
        ).setParameter("token", token).setMaxResults(2)
        if (lock) query.setLockMode(LockModeType.PESSIMISTIC_WRITE)
        return query.resultList.singleOrNull()
    }
}

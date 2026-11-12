package app.campaign.workspace

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.model.Wish
import app.campaign.model.WishSource
import app.testing.fixedClock
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class WorkspaceServiceTests @Autowired constructor(
    private val service: WorkspaceService,
    private val catalog: SeedCatalog,
    private val entityManager: EntityManager,
    private val transactionManager: PlatformTransactionManager,
    private val analysisRuns: AnalysisRunService,
) {
    @Test
    fun `two workspaces persist separate complete seed records`() {
        val first = service.create()
        val second = service.create()
        entityManager.flush()
        entityManager.clear()
        assertNotEquals(first.id, second.id)
        assertNotEquals(first.token, second.token)
        val savedWorkspace = service.findByToken(first.token)!!
        assertEquals(first.id, savedWorkspace.id)
        val firstOptions = service.listOptions(first.token)
        val secondOptions = service.listOptions(second.token)
        assertEquals(3, firstOptions.size)
        assertEquals(3, secondOptions.size)
        assertTrue(firstOptions.all { it.workspaceId == first.id })
        assertTrue(secondOptions.all { it.workspaceId == second.id })
        assertTrue(firstOptions.map { it.id }.intersect(secondOptions.map { it.id }.toSet()).isEmpty())
        for (seed in catalog.definition.options) {
            val saved = firstOptions.single { it.id == catalog.optionId(first.id, seed.key) }
            assertEquals(seed.name, saved.name)
            assertEquals(seed.fixedAttributes, saved.fixedAttributes)
            assertEquals(seed.allowedVariants, saved.allowedVariants)
            assertEquals(seed.unitCost, saved.unitCost)
            assertEquals(seed.minBatch, saved.minBatch)
            assertEquals(seed.maxCapacity, saved.maxCapacity)
        }
        val firstWishes = service.listWishes(first.token)
        val secondWishes = service.listWishes(second.token)
        assertEquals(40, firstWishes.size)
        assertEquals(40, secondWishes.size)
        assertTrue(firstWishes.map { it.id }.intersect(secondWishes.map { it.id }.toSet()).isEmpty())
        for (seed in catalog.wishes) {
            val saved = firstWishes.single { it.id == catalog.wishId(first.id, seed.key) }
            assertEquals(seed.text, saved.text)
            assertEquals(seed.displayName, saved.displayName)
            assertEquals(WishSource.IMPORTED, saved.source)
            assertEquals(savedWorkspace.createdAt, saved.createdAt)
            assertEquals(first.id, saved.workspaceId)
            assertFalse(saved.hidden)
        }
        service.addWish(first.token, "One more postcard set for me")
        assertEquals(41, service.listWishes(first.token).size)
        assertEquals(40, service.listWishes(second.token).size)
        assertFailsWith<WishNotFoundException> { service.setWishHidden(second.token, firstWishes.first().id, true) }
        assertFalse(service.listWishes(first.token).first { it.id == firstWishes.first().id }.hidden)
    }

    @Test
    fun `tokens have 256 bits and malformed missing or altered tokens fail closed`() {
        val tokens = (1..20).map { service.create().token }
        assertEquals(tokens.size, tokens.toSet().size)
        assertTrue(tokens.all { it.matches(Regex("[A-Za-z0-9_-]{43}")) })
        assertTrue(tokens.all { Base64.getUrlDecoder().decode(it).size == 32 })
        val token = tokens.first()
        val altered = (if (token[0] == 'A') "B" else "A") + token.drop(1)
        for (missing in listOf("", " ", "../", "' OR '1'='1", "x".repeat(43), altered, token.dropLast(1), " $token")) {
            assertNull(service.findByToken(missing))
            assertFailsWith<WorkspaceNotFoundException> { service.listWishes(missing) }
            assertFailsWith<WorkspaceNotFoundException> { service.listOptions(missing) }
            assertFailsWith<WorkspaceNotFoundException> { service.addWish(missing, "A set please") }
            assertFailsWith<WorkspaceNotFoundException> { service.setWishHidden(missing, UUID.randomUUID(), true) }
        }
    }

    @Test
    fun `intake trims text and optional names and enforces length limits`() {
        val now = Instant.parse("2026-03-04T05:06:07Z")
        val timedService = WorkspaceService(entityManager, catalog, fixedClock(now), analysisRuns)
        val workspace = timedService.create()
        assertEquals(now, workspace.createdAt)
        val wish = timedService.addWish(workspace.token, " \n A matte set, please! \t", "  Fern  ")
        assertEquals("A matte set, please!", wish.text)
        assertEquals("Fern", wish.displayName)
        assertEquals(WishSource.ONSITE, wish.source)
        assertEquals(now, wish.createdAt)
        assertFalse(wish.hidden)
        assertNull(timedService.addWish(workspace.token, "A5 please").displayName)
        assertNull(timedService.addWish(workspace.token, "Stickers too", " \n ").displayName)
        assertEquals(500, timedService.addWish(workspace.token, " ${"x".repeat(500)} ", "n".repeat(255)).text.length)
        entityManager.flush()
        entityManager.clear()
        assertEquals("Fern", service.listWishes(workspace.token).single { it.id == wish.id }.displayName)
        for (text in listOf("", " \n\t", "x".repeat(501))) {
            assertFailsWith<IllegalArgumentException> { service.addWish(workspace.token, text) }
        }
        assertFailsWith<IllegalArgumentException> { service.addWish(workspace.token, "A set", "n".repeat(256)) }
        assertEquals(44, service.listWishes(workspace.token).size)
    }

    @Test
    fun `import trims lines and reports length paste and workspace limits`() {
        val workspace = service.create()
        repeat(159) { service.addWish(workspace.token, "Existing $it") }
        service.setWishHidden(workspace.token, catalog.wishId(workspace.id, "w01"), true)
        val lines = listOf("  Imported wish  ", " ", "x".repeat(501), "Another wish") +
            List(96) { "" } + listOf("Beyond paste limit")
        val result = service.addImportedWishes(workspace.token, lines.joinToString("\n"))
        assertEquals(ImportedWishResult(1, 1, 1, 1), result)
        assertEquals(3, result.skipped)
        entityManager.flush()
        entityManager.clear()
        val imported = service.listWishes(workspace.token).single { it.text == "Imported wish" }
        assertEquals(WishSource.IMPORTED, imported.source)
        assertNull(imported.displayName)
        assertEquals(200, service.listWishes(workspace.token).size)
        assertFailsWith<WorkspaceNotFoundException> { service.addImportedWishes("missing", "") }
    }

    @Test
    fun `hidden wishes persist and can be unhidden without changing the other workspace`() {
        val first = service.create()
        val second = service.create()
        val wishId = catalog.wishId(first.id, "w01")
        service.setWishHidden(first.token, wishId, true)
        entityManager.flush()
        entityManager.clear()
        assertTrue(service.listWishes(first.token).single { it.id == wishId }.hidden)
        assertEquals(39, service.listWishes(first.token, includeHidden = false).size)
        assertEquals(40, service.listWishes(second.token, includeHidden = false).size)
        service.setWishHidden(first.token, wishId, false)
        entityManager.flush()
        entityManager.clear()
        assertFalse(service.listWishes(first.token).single { it.id == wishId }.hidden)
        assertEquals(40, service.listWishes(first.token, includeHidden = false).size)
        assertFailsWith<WishNotFoundException> { service.setWishHidden(first.token, UUID.randomUUID(), true) }
    }

    @Test
    fun `cap includes seed and hidden wishes and is isolated per workspace`() {
        val first = service.create()
        val second = service.create()
        repeat(160) { service.addWish(first.token, "A set for gift $it") }
        service.setWishHidden(first.token, catalog.wishId(first.id, "w01"), true)
        assertEquals(200, service.listWishes(first.token).size)
        assertFailsWith<WishLimitReachedException> { service.addWish(first.token, "One too many") }
        assertEquals(199, service.listWishes(first.token, includeHidden = false).size)
        service.addWish(second.token, "There is room here")
        assertEquals(41, service.listWishes(second.token).size)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `concurrent intake cannot exceed the final slot`() {
        val workspace = service.create()
        val transaction = TransactionTemplate(transactionManager)
        val executor = Executors.newFixedThreadPool(2)
        try {
            transaction.executeWithoutResult { repeat(159) { service.addWish(workspace.token, "Gift set $it") } }
            val start = CountDownLatch(1)
            val submissions = (1..2).map {
                executor.submit<Result<Wish>> {
                    check(start.await(10, TimeUnit.SECONDS))
                    runCatching { service.addWish(workspace.token, "The last set, please") }
                }
            }
            start.countDown()
            val results = submissions.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.exceptionOrNull() is WishLimitReachedException })
            assertEquals(200, service.listWishes(workspace.token).size)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            transaction.executeWithoutResult {
                for (entity in listOf("AnalysisRunQuota", "AnalysisRun", "Wish", "Option")) {
                    entityManager.createQuery("delete from $entity e where e.workspaceId = :id")
                        .setParameter("id", workspace.id).executeUpdate()
                }
                entityManager.createQuery("delete from Workspace w where w.id = :id")
                    .setParameter("id", workspace.id).executeUpdate()
            }
        }
    }
}

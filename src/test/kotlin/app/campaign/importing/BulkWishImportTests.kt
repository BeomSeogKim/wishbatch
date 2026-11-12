package app.campaign.importing

import app.campaign.model.Wish
import app.campaign.model.WishSource
import app.campaign.rules.analysisInputHash
import app.campaign.workspace.ImportedWishResult
import app.campaign.workspace.WorkspaceService
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class BulkWishImportTests @Autowired constructor(
    private val service: WorkspaceService,
    private val entityManager: EntityManager,
    private val transactionManager: PlatformTransactionManager,
) {
    @Test
    fun `intra batch and repeated paste retain one original wish and count only extra occurrences`() {
        val workspace = service.create()
        val original = "  A matte\tpostcard set please  "
        val paste = "$original\nA matte postcard set please\nA matte\u00a0postcard set please"
        assertEquals(ImportedWishResult(1, 0, 0, 0, 2), service.addImportedWishes(workspace.token, paste))
        val first = service.listWishes(workspace.token).single { it.text == original }
        assertEquals(WishSource.IMPORTED, first.source)
        val inputHash = analysisInputHash(service.listOptions(workspace.token), service.listWishes(workspace.token))
        assertEquals(ImportedWishResult(0, 0, 0, 0, 3), service.addImportedWishes(workspace.token, paste))
        entityManager.flush()
        entityManager.clear()
        val saved = service.listWishes(workspace.token).single { it.id == first.id }
        assertEquals(original, saved.text)
        assertEquals(5, saved.duplicateCount)
        assertEquals(41, service.listWishes(workspace.token, includeHidden = false).size)
        assertEquals(inputHash, analysisInputHash(service.listOptions(workspace.token), service.listWishes(workspace.token)))
    }

    @Test
    fun `duplicates are scoped to workspace and preserve hidden onsite evidence`() {
        val first = service.create()
        val second = service.create()
        val onsite = service.addWish(first.token, "A special matte set", "A fan")
        service.setWishHidden(first.token, onsite.id, true)
        assertEquals(ImportedWishResult(0, 0, 0, 0, 1),
            service.addImportedWishes(first.token, "A special  matte set"))
        assertEquals(ImportedWishResult(1, 0, 0, 0),
            service.addImportedWishes(second.token, "A special matte set"))
        entityManager.flush()
        entityManager.clear()
        val saved = service.listWishes(first.token).single { it.id == onsite.id }
        assertEquals(1, saved.duplicateCount)
        assertEquals("A special matte set", saved.text)
        assertEquals("A fan", saved.displayName)
        assertEquals(WishSource.ONSITE, saved.source)
        assertEquals(true, saved.hidden)
        val other = service.listWishes(second.token).single { it.text == saved.text }
        assertEquals(0, other.duplicateCount)
        assertFalse(other.hidden)
    }

    @Test
    fun `numbers negations variants punctuation and case remain distinct`() {
        val token = service.create().token
        val wishes = listOf(
            "I want 5 matte cards", "I want 6 matte cards", "I do not want 5 matte cards",
            "I want 5 glossy cards", "I want 5 matte cards?", "I want 5 matte Cards",
            "Budget 1.50", "Budget 150", "Size A6", "Size A5", "Size M", "Size L",
        )
        val result = service.addImportedWishes(token, wishes.joinToString("\n"))
        assertEquals(ImportedWishResult(wishes.size, 0, 0, 0), result)
        val saved = service.listWishes(token).filter { it.text in wishes }
        assertEquals(wishes.toSet(), saved.map { it.text }.toSet())
        assertEquals(0, saved.sumOf { it.duplicateCount })
    }

    @Test
    fun `full workspaces merge valid repeats but rejected lines never inflate duplicates`() {
        val token = service.create().token
        repeat(159) { service.addWish(token, "Existing $it") }
        val original = "Boundary wish"
        assertEquals(ImportedWishResult(1, 0, 0, 0), service.addImportedWishes(token, original))
        val lines = listOf(original, " $original ", original + " ".repeat(501), "No room") +
            List(96) { "" } + original
        val result = service.addImportedWishes(token, lines.joinToString("\n"))
        assertEquals(ImportedWishResult(0, 1, 1, 1, 2), result)
        assertEquals(5, result.added + result.merged + result.skipped)
        assertEquals(2, service.listWishes(token).single { it.text == original }.duplicateCount)
        assertEquals(200, service.listWishes(token).size)
        val empty = service.addImportedWishes(token, "\n \n\t")
        assertEquals(ImportedWishResult(0, 0, 0, 0), empty)
    }

    @Test
    fun `only the first hundred lines count including repeats and five hundred characters fit`() {
        val token = service.create().token
        val original = "x".repeat(500)
        val result = service.addImportedWishes(token, List(101) { original }.joinToString("\r\n"))
        assertEquals(ImportedWishResult(1, 0, 0, 1, 99), result)
        assertEquals(99, service.listWishes(token).single { it.text == original }.duplicateCount)
        assertEquals(41, service.listWishes(token).size)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `visibility waits for import so neither duplicate count nor hidden state is overwritten`() {
        val workspace = service.create()
        service.addImportedWishes(workspace.token, "Protected wish")
        val wish = service.listWishes(workspace.token).single { it.text == "Protected wish" }
        val transaction = TransactionTemplate(transactionManager)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val started = CountDownLatch(1)
            val hiding = transaction.execute {
                service.addImportedWishes(workspace.token, wish.text)
                executor.submit<Wish> {
                    started.countDown()
                    service.setWishHidden(workspace.token, wish.id, true)
                }.also {
                    assertTrue(started.await(10, TimeUnit.SECONDS))
                    assertFailsWith<TimeoutException> { it.get(1, TimeUnit.SECONDS) }
                }
            }!!
            hiding.get(10, TimeUnit.SECONDS)
            val saved = service.listWishes(workspace.token).single { it.id == wish.id }
            assertEquals(1, saved.duplicateCount)
            assertTrue(saved.hidden)
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

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `concurrent imports serialize duplicates and the final capacity slot`() {
        val workspace = service.create()
        val transaction = TransactionTemplate(transactionManager)
        val executor = Executors.newFixedThreadPool(2)
        try {
            transaction.executeWithoutResult { repeat(159) { service.addWish(workspace.token, "Existing $it") } }
            val start = CountDownLatch(1)
            val imports = (1..2).map { index ->
                executor.submit<ImportedWishResult> {
                    check(start.await(10, TimeUnit.SECONDS))
                    service.addImportedWishes(workspace.token,
                        "The final wish\nThe final  wish\nThe final wish\nAnother wish $index")
                }
            }
            start.countDown()
            val results = imports.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, results.sumOf { it.added })
            assertEquals(5, results.sumOf { it.merged })
            assertEquals(2, results.sumOf { it.skipped })
            val wishes = service.listWishes(workspace.token)
            assertEquals(200, wishes.size)
            assertEquals(5, wishes.single { it.text == "The final wish" }.duplicateCount)
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

package app.campaign.analysisrun

import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.model.WishSource
import app.campaign.port.AnalysisFailure
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalysisResult
import app.campaign.port.AnalysisValidationFlag
import app.campaign.port.AnalysisValidationResult
import app.campaign.port.AnalysisValidationRule
import app.campaign.port.AnalyzedWish
import app.campaign.provenance.DecisionRecordQuery
import app.campaign.rules.analysisInputHash
import app.campaign.workspace.WorkspaceNotFoundException
import app.campaign.workspace.WorkspaceService
import app.testing.InMemoryWishAnalyzer
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(AnalysisRunServiceTests.Fakes::class)
class AnalysisRunServiceTests @Autowired constructor(
    private val runs: AnalysisRunService,
    private val workspaces: WorkspaceService,
    private val analyzer: InMemoryWishAnalyzer,
    private val entityManager: EntityManager,
    private val transactionManager: PlatformTransactionManager,
    private val records: DecisionRecordQuery,
) {
    private val transaction = TransactionTemplate(transactionManager)

    @TestConfiguration(proxyBeanMethods = false)
    class Fakes {
        @Bean
        @Primary
        fun testAnalyzer() = InMemoryWishAnalyzer()
    }

    @BeforeEach
    fun clearScripts() {
        analyzer.results.clear()
    }

    @Test
    fun `creation attaches isolated immutable seed runs without invoking the analyzer`() {
        val before = analyzer.calls.size
        val first = workspaces.create()
        val second = workspaces.create()
        val a = runs.latest(first.token)!!
        val b = runs.latest(second.token)!!
        assertTrue(a.run.isSeed && b.run.isSeed)
        assertNotEquals(a.run.id, b.run.id)
        assertEquals(first.id, a.run.workspaceId)
        assertEquals(analysisInputHash(workspaces.listOptions(first.token), workspaces.listWishes(first.token)), a.run.inputHash)
        assertTrue(a.output.wishes.map { it.wishId }.toSet().intersect(b.output.wishes.map { it.wishId }.toSet()).isEmpty())
        assertEquals(0, a.newWishCount)
        assertEquals(0, runs.liveRunCount(first.token))
        assertEquals(before, analyzer.calls.size)
        val snapshots = records.findByToken(first.token)!!.sources
        val snapshot = snapshots.single()
        assertEquals("ANALYSIS", snapshot.sourceType)
        assertEquals(a.run.id, snapshot.sourceId)
        assertTrue(snapshot.intact)
        assertTrue(snapshot.payload["seed"].asBoolean())
        assertEquals(workspaces.listWishes(first.token).associate { it.id.toString() to it.text },
            snapshot.payload["wishes"].toList().associate { it["wishId"].asString() to it["text"].asString() })
        val wish = workspaces.listWishes(first.token).first()
        transaction.executeWithoutResult {
            entityManager.createNativeQuery("update wishes set text = :text where id = :id")
                .setParameter("text", "Changed after seed analysis").setParameter("id", wish.id).executeUpdate()
        }
        assertEquals(snapshots, records.findByToken(first.token)!!.sources)
    }

    @Test
    fun `success persists decoded flags and the previous snapshot remains unchanged`() {
        val workspace = workspaces.create()
        val seed = runs.latest(workspace.token)!!
        val flag = AnalysisValidationFlag(AnalysisValidationRule.V2, "proposal.rationale[0]", "Ungrounded quote dropped.")
        val success = success(workspace.token).let { it.copy(validated = it.validated.copy(flags = listOf(flag))) }
        analyzer.results.add(success)
        val saved = assertIs<AnalysisRunResult.Completed>(runs.run(workspace.token)).stored
        assertFalse(saved.run.isSeed)
        assertEquals(saved.run.id, runs.latest(workspace.token)!!.run.id)
        assertEquals(listOf(flag), runs.latest(workspace.token)!!.flags)
        assertEquals(success.validated.output, runs.latest(workspace.token)!!.output)
        assertEquals(seed.run.resultJson, entityManager.find(app.campaign.model.AnalysisRun::class.java, seed.run.id).resultJson)
        assertEquals(1, runs.liveRunCount(workspace.token))
    }

    @Test
    fun `all typed failures preserve the last run and only configured attempts consume quota`() {
        val workspace = workspaces.create()
        val seed = runs.latest(workspace.token)!!.run.id
        val snapshots = records.findByToken(workspace.token)!!.sources
        val before = analyzer.calls.size
        for (failure in AnalysisFailure.entries) {
            analyzer.results.add(AnalysisResult.Failure(failure))
            assertEquals(failure, assertIs<AnalysisRunResult.Failed>(runs.run(workspace.token)).reason)
            assertEquals(seed, runs.latest(workspace.token)!!.run.id)
            assertEquals(snapshots, records.findByToken(workspace.token)!!.sources)
        }
        assertEquals(before + AnalysisFailure.entries.size, analyzer.calls.size)
        assertEquals(4, runs.liveRunCount(workspace.token))
        assertEquals(4, runs.liveRunCount(workspace.token))
    }

    @Test
    fun `ten attempts cap failed and successful calls without counting the seed or other workspaces`() {
        val first = workspaces.create()
        val second = workspaces.create()
        val before = analyzer.calls.size
        repeat(5) {
            analyzer.results.add(success(first.token))
            assertIs<AnalysisRunResult.Completed>(runs.run(first.token))
            analyzer.results.add(AnalysisResult.Failure(AnalysisFailure.TIMEOUT))
            assertIs<AnalysisRunResult.Failed>(runs.run(first.token))
        }
        val last = runs.latest(first.token)!!.run.id
        assertEquals(AnalysisFailure.RUN_LIMIT_REACHED, assertIs<AnalysisRunResult.Failed>(runs.run(first.token)).reason)
        assertEquals(before + 10, analyzer.calls.size)
        assertEquals(10, runs.liveRunCount(first.token))
        assertEquals(last, runs.latest(first.token)!!.run.id)
        assertEquals(0, runs.liveRunCount(second.token))
        analyzer.results.add(success(second.token))
        assertIs<AnalysisRunResult.Completed>(runs.run(second.token))
    }

    @Test
    fun `visible new wishes are counted for seed and live snapshots independently of hidden old wishes`() {
        val workspace = workspaces.create()
        val added = workspaces.addWish(workspace.token, "One more small print")
        assertEquals(1, runs.latest(workspace.token)!!.newWishCount)
        workspaces.setWishHidden(workspace.token, added.id, true)
        assertEquals(0, runs.latest(workspace.token)!!.newWishCount)
        workspaces.setWishHidden(workspace.token, added.id, false)
        val old = workspaces.listWishes(workspace.token).first { it.id != added.id }
        workspaces.setWishHidden(workspace.token, old.id, true)
        val completed = Instant.now().plusSeconds(1)
        analyzer.results.add(success(workspace.token, completed))
        val live = assertIs<AnalysisRunResult.Completed>(runs.run(workspace.token)).stored.run
        val snapshot = records.findByToken(workspace.token)!!.sources.single { it.sourceId == live.id }
        assertTrue(snapshot.intact)
        assertFalse(snapshot.payload["seed"].asBoolean())
        assertEquals(analyzer.calls.last().wishes.associate { it.id.toString() to it.text },
            snapshot.payload["wishes"].toList().associate { it["wishId"].asString() to it["text"].asString() })
        assertFalse(snapshot.payload["wishes"].toList().any { it["wishId"].asString() == old.id.toString() })
        assertEquals(0, runs.latest(workspace.token)!!.newWishCount)
        workspaces.setWishHidden(workspace.token, old.id, false)
        assertEquals(0, runs.latest(workspace.token)!!.newWishCount)
        transaction.executeWithoutResult {
            entityManager.persist(Wish(workspace.id, "Another request after the run", WishSource.ONSITE,
                createdAt = completed.plusSeconds(1)))
        }
        assertEquals(1, runs.latest(workspace.token)!!.newWishCount)
        assertEquals(40, analyzer.calls.last().wishes.size)
        transaction.executeWithoutResult {
            entityManager.createNativeQuery("update wishes set text = :text where id = :id")
                .setParameter("text", "Changed after live analysis").setParameter("id", added.id).executeUpdate()
        }
        assertEquals(snapshot, records.findByToken(workspace.token)!!.sources.single { it.sourceId == live.id })
    }

    @Test
    fun `empty visible input avoids a call and wrong workspace tokens fail closed`() {
        val workspace = workspaces.create()
        workspaces.listWishes(workspace.token).forEach { workspaces.setWishHidden(workspace.token, it.id, true) }
        val before = analyzer.calls.size
        assertEquals(AnalysisRunResult.NoVisibleWishes, runs.run(workspace.token))
        assertEquals(before, analyzer.calls.size)
        assertEquals(0, runs.liveRunCount(workspace.token))
        for (token in listOf("invalid", "x".repeat(43))) {
            assertFailsWith<WorkspaceNotFoundException> { runs.latest(token) }
            assertFailsWith<WorkspaceNotFoundException> { runs.run(token) }
            assertFailsWith<WorkspaceNotFoundException> { runs.liveRunCount(token) }
        }
    }

    @Test
    fun `concurrent calls cannot both consume the last available run`() {
        val workspace = workspaces.create()
        val executor = Executors.newFixedThreadPool(2)
        val before = analyzer.calls.size
        try {
            repeat(9) {
                analyzer.results.add(AnalysisResult.Failure(AnalysisFailure.TIMEOUT))
                runs.run(workspace.token)
            }
            analyzer.results.add(success(workspace.token))
            val start = CountDownLatch(1)
            val calls = (1..2).map {
                executor.submit<AnalysisRunResult> {
                    check(start.await(10, TimeUnit.SECONDS))
                    runs.run(workspace.token)
                }
            }
            start.countDown()
            val results = calls.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is AnalysisRunResult.Completed })
            assertEquals(1, results.count { it == AnalysisRunResult.Failed(AnalysisFailure.RUN_LIMIT_REACHED) })
            assertEquals(before + 10, analyzer.calls.size)
            assertEquals(10, runs.liveRunCount(workspace.token))
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            transaction.executeWithoutResult {
                for (entity in listOf("DecisionSourceSnapshot", "AnalysisProgressJob", "AnalysisRunQuota", "AnalysisRun", "Wish", "Option")) {
                    entityManager.createQuery("delete from $entity e where e.workspaceId = :id")
                        .setParameter("id", workspace.id).executeUpdate()
                }
                entityManager.createQuery("delete from Workspace w where w.id = :id")
                    .setParameter("id", workspace.id).executeUpdate()
            }
        }
    }

    private fun success(token: String, completedAt: Instant = Instant.now()): AnalysisResult.Success {
        val options = workspaces.listOptions(token)
        val wishes = workspaces.listWishes(token, includeHidden = false)
        val output = AnalysisOutput(
            wishes.map { AnalyzedWish(it.id, WishKind.NOT_A_REQUEST, emptyList()) },
            emptyList(), null, "No product requests in this snapshot.", emptyList(), emptyList(), emptyList(),
            options.map { it.id },
        )
        return AnalysisResult.Success("test-model", AnalysisValidationResult.Accepted(output, emptyList()),
            analysisInputHash(options, wishes), completedAt)
    }
}

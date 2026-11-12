package app.campaign.progress

import app.campaign.analysisrun.AnalysisRunResult
import app.campaign.analysisrun.AnalysisRunService
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.port.AnalysisFailure
import app.campaign.port.AnalysisMetrics
import app.campaign.port.AnalysisTokenUsage
import app.campaign.port.AnalysisObserver
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalysisProgressEvent
import app.campaign.port.AnalysisResult
import app.campaign.port.AnalysisValidationFlag
import app.campaign.port.AnalysisValidationResult
import app.campaign.port.AnalysisValidationRule
import app.campaign.port.AnalyzedWish
import app.campaign.port.WishAnalyzer
import app.campaign.port.WishVerdict
import app.campaign.provenance.DecisionRecordQuery
import app.campaign.rules.analysisInputHash
import app.campaign.workspace.WorkspaceNotFoundException
import app.campaign.workspace.WorkspaceService
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(AnalysisProgressServiceTests.Fakes::class)
class AnalysisProgressServiceTests @Autowired constructor(
    private val progress: AnalysisProgressService,
    private val store: AnalysisProgressStore,
    private val runs: AnalysisRunService,
    private val workspaces: WorkspaceService,
    private val analyzer: ProgressAnalyzer,
    private val records: DecisionRecordQuery,
    private val entities: EntityManager,
    private val mapper: JsonMapper,
    manager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(manager)

    @TestConfiguration(proxyBeanMethods = false)
    class Fakes {
        @Bean
        @Primary
        fun progressAnalyzer() = ProgressAnalyzer()
    }

    @Test
    fun `async progress deduplicates readings and preserves the successful source snapshot without locking intake`() {
        val workspace = workspaces.create()
        val options = workspaces.listOptions(workspace.token)
        val wishes = workspaces.listWishes(workspace.token, includeHidden = false)
        val originalSeed = runs.latest(workspace.token)!!.run.id
        val emitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val flag = AnalysisValidationFlag(AnalysisValidationRule.V2, "wishes[0].needs[0]", "Ungrounded quote dropped.")
        val metrics = AnalysisMetrics(2.75, AnalysisTokenUsage(125, 80, null), usageComplete = false)
        analyzer.scripts[workspace.id] = { inputOptions, inputWishes, observer ->
            for (reading in 1..2) {
                observer.onProgress(AnalysisProgressEvent("CLASSIFY_VALIDATED", reading, 1, 1, flags = listOf(flag)))
                observer.onProgress(AnalysisProgressEvent("BATCH_COMPLETED", reading, 1, 1,
                    processedWishIds = inputWishes.take(2).map { it.id }, metrics = metrics))
            }
            emitted.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            success(inputOptions, inputWishes, listOf(flag))
        }
        val job = assertIs<ProgressStart.Started>(progress.start(workspace.token)).job
        try {
            assertTrue(emitted.await(5, TimeUnit.SECONDS))
            val intermediate = progress.find(workspace.token, job.id)!!
            assertEquals("RUNNING", intermediate.status)
            assertEquals(metrics, intermediate.metrics)
            assertNull(intermediate.failureCategory)
            assertEquals(2, intermediate.processedWishes)
            assertEquals(2, intermediate.completedBatches)
            assertEquals(mapOf("V2" to 2), intermediate.validationCounts)
            assertTrue(intermediate.finalCounts.isEmpty())
            assertEquals(listOf(1, 2, 3, 4), intermediate.events.map { it.sequence })
            assertEquals(listOf(0, 2, 2, 2), intermediate.events.map { it.processedWishes })
            assertEquals(analysisInputHash(options, wishes), intermediate.inputHash)
            assertEquals(wishes.map { it.id }.toSet(), intermediate.sourceWishIds.toSet())
            assertFalse(analyzer.calls.single { it.workspaceId == workspace.id }.transactionActive)
            Executors.newSingleThreadExecutor().use { executor ->
                val added = executor.submit<Wish> { workspaces.addWish(workspace.token, "A later request during analysis") }
                    .get(2, TimeUnit.SECONDS)
                assertFalse(added.id in intermediate.sourceWishIds)
            }
            assertEquals(originalSeed, runs.latest(workspace.token)!!.run.id)
        } finally {
            release.countDown()
        }
        val completed = terminal(workspace.token, job.id)
        assertEquals("COMPLETED", completed.status)
        assertEquals(metrics, completed.metrics)
        assertEquals(wishes.size, completed.processedWishes)
        assertEquals(mapOf("V2" to 2), completed.validationCounts)
        assertEquals(options.size, completed.finalCounts.size)
        assertTrue(completed.finalCounts.all { it.compatible == wishes.size && it.depends == 0 && it.notServed == 0 })
        val saved = runs.latest(workspace.token)!!
        assertEquals(completed.runId, saved.run.id)
        assertFalse(saved.run.isSeed)
        assertEquals(1, saved.newWishCount)
        assertEquals(listOf(flag), saved.flags)
        assertEquals(wishes.map { it.id }.toSet(), saved.output.wishes.map { it.wishId }.toSet())
        val sources = records.findByToken(workspace.token)!!.sources
        assertTrue(sources.single { it.sourceId == originalSeed }.intact)
        val source = sources.single { it.sourceId == completed.runId }
        assertTrue(source.intact)
        assertFalse(source.payload["seed"].asBoolean())
        assertEquals(wishes.associate { it.id.toString() to it.text },
            source.payload["wishes"].toList().associate { it["wishId"].asString() to it["text"].asString() })
    }

    @Test
    fun `completed result metrics replace provisional metrics and persist with the analysis`() {
        val workspace = workspaces.create()
        val provisional = AnalysisMetrics(1.5)
        val final = AnalysisMetrics(4.25, AnalysisTokenUsage(220, 140, 20), usageComplete = true)
        analyzer.scripts[workspace.id] = { options, wishes, observer ->
            observer.onProgress(AnalysisProgressEvent("PROPOSAL_STARTED", metrics = provisional))
            val result = success(options, wishes)
            result.copy(validated = result.validated.copy(output = result.validated.output.copy(metrics = final)))
        }
        val job = assertIs<ProgressStart.Started>(progress.start(workspace.token)).job
        val completed = terminal(workspace.token, job.id)
        assertEquals("COMPLETED", completed.status)
        assertEquals(final, completed.metrics)
        assertEquals(final, runs.latest(workspace.token)!!.output.metrics)
        assertNull(completed.failureCategory)
    }

    @Test
    fun `failed analysis retains the latest valid run provenance and provisional validation summary`() {
        val workspace = workspaces.create()
        analyzer.scripts[workspace.id] = { options, wishes, _ -> success(options, wishes) }
        val successful = assertIs<ProgressStart.Started>(progress.start(workspace.token)).job
        val complete = terminal(workspace.token, successful.id)
        assertEquals("COMPLETED", complete.status)
        val before = runs.latest(workspace.token)!!
        val sources = records.findByToken(workspace.token)!!.sources
        val flag = AnalysisValidationFlag(AnalysisValidationRule.V2, "proposal.rationale[0]", "Ungrounded quote dropped.")
        val metrics = AnalysisMetrics(12.5, AnalysisTokenUsage(200, null, null), failureCategory = "VALIDATION")
        analyzer.scripts[workspace.id] = { _, wishes, observer ->
            observer.onProgress(AnalysisProgressEvent("BATCH_COMPLETED", 1, 1, 1,
                processedWishIds = wishes.take(1).map { it.id }))
            observer.onProgress(AnalysisProgressEvent("PROPOSAL_VALIDATED", flags = listOf(flag)))
            AnalysisResult.Failure(AnalysisFailure.INVALID_OUTPUT, listOf(flag), metrics)
        }
        val failed = assertIs<ProgressStart.Started>(progress.start(workspace.token)).job
        val finished = terminal(workspace.token, failed.id)
        assertEquals("FAILED", finished.status)
        assertEquals("INVALID_OUTPUT", finished.failure)
        assertEquals(metrics, finished.metrics)
        assertEquals("VALIDATION", finished.failureCategory)
        assertEquals(1, finished.processedWishes)
        assertEquals(mapOf("V2" to 1), finished.validationCounts)
        assertTrue(finished.finalCounts.isEmpty())
        assertNull(finished.runId)
        assertEquals(before.run.id, runs.latest(workspace.token)!!.run.id)
        assertEquals(sources, records.findByToken(workspace.token)!!.sources)
        assertEquals(2, runs.liveRunCount(workspace.token))
    }

    @Test
    fun `simultaneous starts share one job and scoped reads reject foreign access`() {
        val workspace = workspaces.create()
        val foreign = workspaces.create()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        analyzer.scripts[workspace.id] = { options, wishes, _ ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            success(options, wishes)
        }
        val start = CountDownLatch(1)
        var jobId: UUID? = null
        Executors.newFixedThreadPool(2).use { executor ->
            val attempts = (1..2).map {
                executor.submit<ProgressStart> {
                    check(start.await(5, TimeUnit.SECONDS))
                    progress.start(workspace.token)
                }
            }
            start.countDown()
            try {
                val results = attempts.map { it.get(5, TimeUnit.SECONDS) }
                val created = results.filterIsInstance<ProgressStart.Started>().single().job
                jobId = created.id
                assertEquals(created.id, results.filterIsInstance<ProgressStart.Existing>().single().job.id)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(created.id, assertIs<ProgressStart.Existing>(progress.start(workspace.token)).job.id)
                assertEquals(1, analyzer.calls.count { it.workspaceId == workspace.id })
                assertEquals(1, runs.liveRunCount(workspace.token))
                assertNull(progress.find(foreign.token, created.id))
                assertNull(progress.latest(foreign.token))
                assertFailsWith<WorkspaceNotFoundException> { progress.find("invalid", created.id) }
                assertFailsWith<WorkspaceNotFoundException> { progress.find("x".repeat(43), created.id) }
                assertFailsWith<WorkspaceNotFoundException> { progress.start("invalid") }
            } finally {
                release.countDown()
            }
        }
        assertEquals("COMPLETED", terminal(workspace.token, requireNotNull(jobId)).status)
    }

    @Test
    fun `executor permits two running and two queued jobs and rejects a fifth workspace`() {
        val workspaces = (1..5).map { workspaces.create() }
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        workspaces.forEach { workspace ->
            analyzer.scripts[workspace.id] = { options, wishes, _ ->
                val running = active.incrementAndGet()
                peak.accumulateAndGet(running, ::maxOf)
                entered.countDown()
                try {
                    check(release.await(5, TimeUnit.SECONDS))
                    success(options, wishes)
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        val jobs = mutableListOf<Pair<String, UUID>>()
        try {
            workspaces.take(2).forEach { workspace ->
                jobs += workspace.token to assertIs<ProgressStart.Started>(progress.start(workspace.token)).job.id
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            workspaces.slice(2..3).forEach { workspace ->
                val job = assertIs<ProgressStart.Started>(progress.start(workspace.token)).job
                jobs += workspace.token to job.id
                assertEquals("QUEUED", progress.find(workspace.token, job.id)!!.status)
            }
            assertEquals(ProgressStart.Busy, progress.start(workspaces.last().token))
            assertNull(progress.latest(workspaces.last().token))
            assertEquals(0, runs.liveRunCount(workspaces.last().token))
            assertEquals(jobs.first().second, assertIs<ProgressStart.Existing>(progress.start(workspaces.first().token)).job.id)
            assertEquals(2, active.get())
            assertEquals(2, analyzer.calls.count { call -> call.workspaceId in workspaces.map { it.id } })
        } finally {
            release.countDown()
            jobs.forEach { (token, id) -> terminal(token, id) }
        }
        assertEquals(2, peak.get())
        jobs.forEach { (token, id) -> assertEquals("COMPLETED", progress.find(token, id)!!.status) }
        assertEquals(4, analyzer.calls.count { call -> call.workspaceId in workspaces.map { it.id } })
    }

    @Test
    fun `analysis entry points suspend caller transactions before committing jobs and calling the model`() {
        val asynchronous = workspaces.create()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        analyzer.scripts[asynchronous.id] = { options, wishes, _ ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            success(options, wishes)
        }
        var jobId: UUID? = null
        try {
            transaction.executeWithoutResult {
                val started = assertIs<ProgressStart.Started>(progress.start(asynchronous.token))
                jobId = started.job.id
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
                assertTrue(entered.await(5, TimeUnit.SECONDS))
            }
        } finally {
            release.countDown()
        }
        assertEquals("COMPLETED", terminal(asynchronous.token, requireNotNull(jobId)).status)
        val synchronous = workspaces.create()
        analyzer.scripts[synchronous.id] = { options, wishes, _ -> success(options, wishes) }
        transaction.executeWithoutResult {
            assertIs<AnalysisRunResult.Completed>(runs.run(synchronous.token))
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
        }
        assertTrue(analyzer.calls.filter { it.workspaceId in setOf(asynchronous.id, synchronous.id) }
            .all { !it.transactionActive })
    }

    @Test
    fun `restart marks older queued and running jobs interrupted without replaying model work`() {
        val previousCalls = analyzer.calls.size
        val jobs = listOf("QUEUED", "RUNNING").map { status ->
            val workspace = workspaces.create()
            val wishes = workspaces.listWishes(workspace.token)
            val job = AnalysisProgressJob(workspace.id, UUID.randomUUID(), "historical-input-hash",
                mapper.writeValueAsString(wishes.map { it.id }), Instant.now(), status,
                mapper.writeValueAsString(ProgressState()))
            transaction.executeWithoutResult { entities.persist(job) }
            workspace.token to job.id
        }
        store.interruptIncomplete()
        jobs.forEach { (token, id) ->
            val interrupted = progress.find(token, id)!!
            assertEquals("INTERRUPTED", interrupted.status)
            assertEquals("PROCESS_INTERRUPTED", interrupted.failure)
            assertNull(interrupted.runId)
            assertTrue(interrupted.finalCounts.isEmpty())
            assertTrue(runs.latest(token)!!.run.isSeed)
        }
        assertEquals(previousCalls, analyzer.calls.size)
    }

    private fun terminal(token: String, id: UUID): AnalysisProgressView {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        val pause = CountDownLatch(1)
        do {
            val view = requireNotNull(progress.find(token, id))
            if (view.status !in setOf("QUEUED", "RUNNING")) return view
            pause.await(10, TimeUnit.MILLISECONDS)
        } while (System.nanoTime() < deadline)
        error("Analysis job did not finish")
    }

    private fun success(options: List<Option>, wishes: List<Wish>, flags: List<AnalysisValidationFlag> = emptyList()): AnalysisResult.Success {
        val output = AnalysisOutput(wishes.map { AnalyzedWish(it.id, WishKind.REQUEST, emptyList()) },
            wishes.flatMap { wish -> options.map { option ->
                WishVerdict(wish.id, option.id, CompatibilityVerdict.COMPATIBLE, "Matches request.")
            } }, null, "No proposal selected.", emptyList(), emptyList(), emptyList(), options.map { it.id })
        return AnalysisResult.Success("test-progress-model", AnalysisValidationResult.Accepted(output, flags),
            analysisInputHash(options, wishes), Instant.now())
    }

    class ProgressAnalyzer : WishAnalyzer {
        data class Call(val workspaceId: UUID, val transactionActive: Boolean)
        val scripts = ConcurrentHashMap<UUID, (List<Option>, List<Wish>, AnalysisObserver) -> AnalysisResult>()
        val calls = ConcurrentLinkedQueue<Call>()

        override fun analyze(options: List<Option>, wishes: List<Wish>): AnalysisResult =
            error("Progress jobs must use the tracked entry point")

        override fun analyzeTracked(options: List<Option>, wishes: List<Wish>, observer: AnalysisObserver): AnalysisResult {
            val workspaceId = wishes.first().workspaceId
            calls += Call(workspaceId, TransactionSynchronizationManager.isActualTransactionActive())
            return requireNotNull(scripts[workspaceId])(options, wishes, observer)
        }
    }
}

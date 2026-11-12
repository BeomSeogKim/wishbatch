package app.web.progress

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalysisResult
import app.campaign.port.AnalysisValidationResult
import app.campaign.port.AnalyzedWish
import app.campaign.port.WishAnalyzer
import app.campaign.progress.AnalysisProgressService
import app.campaign.progress.AnalysisProgressView
import app.campaign.progress.ProgressStart
import app.campaign.rules.analysisInputHash
import app.campaign.workspace.WorkspaceService
import app.web.security.securePost
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(AnalysisAdmissionTests.Fakes::class)
class AnalysisAdmissionTests @Autowired constructor(
    private val mvc: MockMvc,
    private val workspaces: WorkspaceService,
    private val progress: AnalysisProgressService,
    private val runs: AnalysisRunService,
    private val analyzer: AdmissionAnalyzer,
) {
    @TestConfiguration(proxyBeanMethods = false)
    class Fakes {
        @Bean
        @Primary
        fun admissionAnalyzer() = AdmissionAnalyzer()
    }

    @Test
    fun `legacy posts and progress starts share two workers two queued slots and release admission`() {
        val spaces = (1..6).map { workspaces.create() }
        val release = CountDownLatch(1)
        val entered = spaces.associate { it.id to CountDownLatch(1) }
        val active = AtomicInteger()
        val peak = AtomicInteger()
        spaces.forEach { workspace ->
            analyzer.scripts[workspace.id] = { options, wishes ->
                val running = active.incrementAndGet()
                peak.accumulateAndGet(running, ::maxOf)
                entered.getValue(workspace.id).countDown()
                try {
                    check(release.await(10, TimeUnit.SECONDS))
                    val output = AnalysisOutput(wishes.map { AnalyzedWish(it.id, WishKind.NOT_A_REQUEST, emptyList()) },
                        emptyList(), null, "No requests.", emptyList(), emptyList(), emptyList(), options.map { it.id })
                    AnalysisResult.Success("test-admission", AnalysisValidationResult.Accepted(output, emptyList()),
                        analysisInputHash(options, wishes), Instant.now())
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        Executors.newSingleThreadExecutor().use { requests ->
            spaces.take(2).forEach { workspace ->
                assertIs<ProgressStart.Started>(progress.start(workspace.token))
                assertTrue(entered.getValue(workspace.id).await(5, TimeUnit.SECONDS))
            }
            val legacy = requests.submit<MvcResult> {
                mvc.securePost("/w/${spaces[2].token}/maker/proposal").andExpect {
                    status { is3xxRedirection() }
                    redirectedUrl("/w/${spaces[2].token}/maker/proposal")
                }.andReturn()
            }
            try {
                val queuedLegacy = await(spaces[2].token) { it.status == "QUEUED" }
                val queuedProgress = assertIs<ProgressStart.Started>(progress.start(spaces[3].token)).job
                assertEquals("QUEUED", progress.find(spaces[3].token, queuedProgress.id)!!.status)
                assertEquals(2, active.get())
                assertEquals(listOf("RUNNING", "RUNNING", "QUEUED", "QUEUED"),
                    spaces.take(4).map { progress.latest(it.token)!!.status })
                assertFalse(legacy.isDone)
                assertEquals(1L, entered.getValue(spaces[2].id).count)
                assertEquals(ProgressStart.Busy, progress.start(spaces[4].token))
                val rejected = mvc.securePost("/w/${spaces[5].token}/maker/proposal").andExpect {
                    status { is3xxRedirection() }
                }.andReturn()
                assertContains(rejected.flashMap["analysisError"].toString(), "analysis limit has been reached")
                spaces.drop(4).forEach { workspace ->
                    assertNull(progress.latest(workspace.token))
                    assertEquals(0, runs.liveRunCount(workspace.token))
                    assertTrue(runs.latest(workspace.token)!!.run.isSeed)
                }
                release.countDown()
                assertContains(legacy.get(5, TimeUnit.SECONDS).flashMap["analysisNotice"].toString(), "Analysis complete")
                spaces.take(4).forEach { workspace ->
                    val finished = await(workspace.token) { it.status == "COMPLETED" }
                    assertEquals(finished.runId, runs.latest(workspace.token)!!.run.id)
                    assertEquals(1, runs.liveRunCount(workspace.token))
                }
                assertEquals("COMPLETED", progress.find(spaces[2].token, queuedLegacy.id)!!.status)
            } finally {
                release.countDown()
            }
        }
        assertIs<ProgressStart.Started>(progress.start(spaces[4].token))
        await(spaces[4].token) { it.status == "COMPLETED" }
        val legacyAfterRelease = mvc.securePost("/w/${spaces[5].token}/maker/proposal").andExpect {
            status { is3xxRedirection() }
        }.andReturn()
        assertContains(legacyAfterRelease.flashMap["analysisNotice"].toString(), "Analysis complete")
        assertEquals(2, peak.get())
        assertEquals(0, active.get())
        assertEquals(6, analyzer.calls.size)
        assertEquals(spaces.map { it.id }.toSet(), analyzer.calls.map { it.workspaceId }.toSet())
        assertTrue(analyzer.calls.all { it.threadName == "analysis-progress" && !it.transactionActive })
    }

    private fun await(token: String, predicate: (AnalysisProgressView) -> Boolean): AnalysisProgressView {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        val pause = CountDownLatch(1)
        do {
            val view = progress.latest(token)
            if (view != null && predicate(view)) return view
            pause.await(10, TimeUnit.MILLISECONDS)
        } while (System.nanoTime() < deadline)
        error("Expected analysis checkpoint was not recorded")
    }

    class AdmissionAnalyzer : WishAnalyzer {
        data class Call(val workspaceId: UUID, val threadName: String, val transactionActive: Boolean)
        val scripts = ConcurrentHashMap<UUID, (List<Option>, List<Wish>) -> AnalysisResult>()
        val calls = ConcurrentLinkedQueue<Call>()
        override fun analyze(options: List<Option>, wishes: List<Wish>): AnalysisResult {
            val workspace = wishes.first().workspaceId
            calls += Call(workspace, Thread.currentThread().name, TransactionSynchronizationManager.isActualTransactionActive())
            return requireNotNull(scripts[workspace])(options, wishes)
        }
    }
}

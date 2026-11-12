package app.web.progress

import app.campaign.port.AnalysisFailure
import app.campaign.progress.AnalysisProgressService
import app.campaign.progress.AnalysisProgressView
import app.campaign.progress.ProgressEventView
import app.campaign.progress.ProgressStart
import app.campaign.rules.OptionWishCounts
import app.campaign.workspace.WorkspaceNotFoundException
import app.campaign.workspace.WorkspaceService
import app.web.security.securePost
import org.junit.jupiter.api.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertFalse

@SpringBootTest
@AutoConfigureMockMvc
class AnalysisProgressControllerTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val workspaces: WorkspaceService,
) {
    @MockitoBean
    private lateinit var progress: AnalysisProgressService

    @Test
    fun `progress page starts with a genuine session CSRF form and no fabricated progress`() {
        val token = workspaces.create().token
        val page = mvc.get("/w/$token/maker/analysis-progress").andExpect {
            status { isOk() }
            view { name("progress/page") }
        }.andReturn()
        val html = page.response.contentAsString
        assertContains(html, "No live progress recorded yet.")
        assertContains(html, "name=\"_csrf\"")
        assertContains(html, "action=\"/w/$token/maker/analysis-progress\"")
        assertFalse(html.contains("id=\"analysis-progress\""))
        mvc.post("/w/$token/maker/analysis-progress") {
            session = page.request.session as MockHttpSession
        }.andExpect { status { isForbidden() } }
        verify(progress, never()).start(token)
    }

    @Test
    fun `verified start redirects to the exact job and duplicate start reuses it`() {
        val token = workspaces.create().token
        val job = job()
        `when`(progress.start(token)).thenReturn(ProgressStart.Started(job), ProgressStart.Existing(job))
        repeat(2) {
            mvc.securePost("/w/$token/maker/analysis-progress").andExpect {
                status { is3xxRedirection() }
                redirectedUrl("/w/$token/maker/analysis-progress?job=${job.id}")
            }
        }
    }

    @Test
    fun `poll and selected page scope job identifiers to the token and never start work`() {
        val token = workspaces.create().token
        val foreign = workspaces.create().token
        val job = job()
        `when`(progress.find(token, job.id)).thenReturn(job)
        `when`(progress.find(foreign, job.id)).thenReturn(null)
        mvc.get("/w/$token/maker/analysis-progress/${job.id}").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(job.id.toString()) }
            jsonPath("$.processedWishes") { value(4) }
            jsonPath("$.validationCounts.V2") { value(1) }
            jsonPath("$.flags") { doesNotExist() }
            jsonPath("$.providerPayload") { doesNotExist() }
        }
        for (path in listOf("/w/$foreign/maker/analysis-progress/${job.id}", "/w/$foreign/maker/analysis-progress?job=${job.id}")) {
            mvc.get(path).andExpect { status { isNotFound() } }
        }
        verify(progress, never()).start(token)
        verify(progress, never()).start(foreign)
    }

    @Test
    fun `active page labels processed wishes provisionally and retains actual validation history`() {
        val token = workspaces.create().token
        val job = job()
        `when`(progress.latest(token)).thenReturn(job)
        val html = mvc.get("/w/$token/maker/analysis-progress").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertContains(html, "4</strong> of <strong")
        assertContains(html, "distinct wishes processed (provisional)")
        assertContains(html, "reading 2")
        assertContains(html, "batch 1/10")
        assertContains(html, "V2: 1")
        assertContains(html, "id=\"progress-final\" hidden=\"hidden\"")
        assertContains(html, "data-poll-url=\"/w/$token/maker/analysis-progress/${job.id}\"")
    }

    @Test
    fun `completed page renders only the final merged counts and the stored run link`() {
        val token = workspaces.create().token
        val option = workspaces.listOptions(token).first()
        val runId = UUID.randomUUID()
        val job = job().copy(status = "COMPLETED", runId = runId,
            finalCounts = listOf(OptionWishCounts(option.id, 2, 1, 0, 1)))
        `when`(progress.latest(token)).thenReturn(job)
        val html = mvc.get("/w/$token/maker/analysis-progress").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertContains(html, "data-count=\"compatible\">2</td>")
        assertContains(html, "data-count=\"depends\">1</td>")
        assertContains(html, "href=\"/w/$token/maker/proposal?run=$runId\"")
        assertFalse(html.contains("id=\"progress-final\" hidden"))
    }

    @Test
    fun `missing progress checkpoints disclose incomplete validation history`() {
        val token = workspaces.create().token
        `when`(progress.latest(token)).thenReturn(job().copy(checkpointIncomplete = true))
        val html = mvc.get("/w/$token/maker/analysis-progress").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertContains(html, "Some progress checkpoints could not be recorded.")
        assertContains(html, "Recorded validation counts may be incomplete. Your last valid analysis remains available.")
        assertFalse(html.contains("id=\"progress-checkpoint-warning\" hidden"))
    }

    @Test
    fun `start rejections remain on the progress page and unknown tokens return not found`() {
        val token = workspaces.create().token
        `when`(progress.start(token)).thenReturn(ProgressStart.Busy, ProgressStart.NoVisibleWishes,
            ProgressStart.Failed(AnalysisFailure.RUN_LIMIT_REACHED))
        repeat(3) {
            mvc.securePost("/w/$token/maker/analysis-progress").andExpect {
                status { is3xxRedirection() }
                redirectedUrl("/w/$token/maker/analysis-progress")
                flash { attributeExists("progressError") }
            }
        }
        `when`(progress.latest("missing")).thenThrow(WorkspaceNotFoundException())
        `when`(progress.start("missing")).thenThrow(WorkspaceNotFoundException())
        mvc.get("/w/missing/maker/analysis-progress").andExpect { status { isNotFound() } }
        mvc.securePost("/w/missing/maker/analysis-progress").andExpect { status { isNotFound() } }
    }

    private fun job() = AnalysisProgressView(
        id = UUID.randomUUID(), status = "RUNNING", totalWishes = 40, processedWishes = 4,
        completedBatches = 2, inputHash = "a".repeat(64), sourceWishIds = listOf(UUID.randomUUID()),
        events = listOf(ProgressEventView(1, Instant.parse("2026-01-01T00:00:00Z"), "BATCH_COMPLETED",
            2, 1, 10, 4, mapOf("V2" to 1))),
        validationCounts = mapOf("V2" to 1), runId = null, finalCounts = emptyList(), failure = null,
    )
}

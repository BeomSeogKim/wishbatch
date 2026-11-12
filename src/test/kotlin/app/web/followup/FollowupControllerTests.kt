package app.web.followup

import app.campaign.followup.FollowupAnswerRecord
import app.campaign.followup.FollowupDraftResult
import app.campaign.followup.FollowupPage
import app.campaign.followup.FollowupPageState
import app.campaign.followup.FollowupQuestion
import app.campaign.followup.FollowupService
import app.campaign.followup.InvalidFollowupException
import app.campaign.model.NeedAttribute
import app.campaign.model.Option
import app.campaign.port.AnalysisFailure
import app.campaign.port.WishAnalyzer
import app.campaign.review.InvalidWishReviewException
import app.campaign.workspace.WorkspaceNotFoundException
import app.campaign.workspace.WorkspaceService
import app.web.security.securePost
import org.junit.jupiter.api.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertFalse

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FollowupControllerTests @Autowired constructor(
    private val mvc: MockMvc,
    private val workspaces: WorkspaceService,
) {
    @MockitoBean
    private lateinit var followups: FollowupService

    @MockitoBean
    private lateinit var analyzer: WishAnalyzer

    private val token = "synthetic-followup-workspace"
    private val workspace = UUID.randomUUID()
    private val run = UUID.randomUUID()
    private val wish = UUID.randomUUID()
    private val now = Instant.parse("2026-01-02T03:04:05Z")
    private val option = Option(workspace, "Art print", "print", emptyMap(),
        mapOf("finish" to listOf("matte", "glossy")), 500, "USD", 3, 10, "Maker-confirmed delivery", "US")

    @Test
    fun `maker sees editable draft choices and approves with genuine session csrf`() {
        val question = question()
        `when`(followups.makerPage(token)).thenReturn(FollowupPage(run, option, listOf(question), null))
        val html = mvc.get("/w/$token/maker/questions").andExpect {
            status { isOk() }; view { name("followup/maker") }
        }.andReturn().response.contentAsString
        assertContains(html, "Questions to ask fans")
        assertContains(html, "Art print")
        assertContains(html, "name=\"text\"")
        assertContains(html, "value=\"matte\"")
        assertContains(html, "value=\"glossy\"")
        assertContains(html, "name=\"_csrf\"")
        assertContains(html, "Approve question")
        assertContains(html, "stated wishes, not orders")
        assertFalse(html.contains("/questions?wish="))
        verify(followups, never()).draft(token)

        `when`(followups.approve(token, question.id, "Which finish works for you?", listOf("matte", "glossy"))).thenReturn(question)
        mvc.securePost("/w/$token/maker/questions/${question.id}/approve") {
            param("text", "Which finish works for you?")
            param("choices", "matte", "glossy")
        }.andExpect {
            status { is3xxRedirection() }; redirectedUrl("/w/$token/maker/questions")
            flash { attribute("questionApproved", true) }
        }
        verify(followups).approve(token, question.id, "Which finish works for you?", listOf("matte", "glossy"))
    }

    @Test
    fun `approved maker question is fixed and provides scoped fan links`() {
        val question = question(approved = true)
        `when`(followups.makerPage(token)).thenReturn(FollowupPage(run, option, listOf(question), null))
        val html = mvc.get("/w/$token/maker/questions").andReturn().response.contentAsString
        assertContains(html, "Approved for fans")
        assertContains(html, "saved answers keep their meaning")
        assertContains(html, "/w/$token/questions?wish=$wish")
        assertFalse(html.contains("Approve question</button>"))
        assertFalse(html.contains("name=\"text\""))
    }

    @Test
    fun `empty maker screen explains draft availability and missing proposed option`() {
        `when`(followups.makerPage(token)).thenReturn(FollowupPage(run, option, emptyList(), null))
        var html = mvc.get("/w/$token/maker/questions").andReturn().response.contentAsString
        assertContains(html, "No follow-up questions are saved")
        assertContains(html, "Draft follow-up questions")
        `when`(followups.makerPage(token)).thenReturn(FollowupPage(run, option, emptyList(), "NOT_CONFIGURED", state = FollowupPageState.FAILED))
        html = mvc.get("/w/$token/maker/questions").andReturn().response.contentAsString
        assertContains(html, "Question drafting was unavailable")
        assertFalse(html.contains("Draft follow-up questions</button>"))
        `when`(followups.makerPage(token)).thenReturn(FollowupPage(null, null, emptyList(), null, state = FollowupPageState.UNAVAILABLE))
        html = mvc.get("/w/$token/maker/questions").andReturn().response.contentAsString
        assertContains(html, "No proposed option to clarify")
        assertFalse(html.contains("Draft follow-up questions</button>"))
    }

    @Test
    fun `stale and ineligible questions explain the next step without draft forms`() {
        for ((state, message) in listOf(
            FollowupPageState.STALE to "Questions need a current analysis",
            FollowupPageState.NO_ELIGIBLE to "No eligible questions yet",
        )) {
            `when`(followups.makerPage(token)).thenReturn(FollowupPage(run, option, emptyList(), null, state = state))
            val html = mvc.get("/w/$token/maker/questions").andExpect { status { isOk() } }
                .andReturn().response.contentAsString
            assertContains(html, message)
            assertContains(html, "/w/$token/maker/proposal")
            assertFalse(html.contains("Draft follow-up questions</button>"))
        }
        verify(followups, never()).draft(token)
    }

    @Test
    fun `drafting is an explicit post and provider failures stay user readable`() {
        `when`(followups.draft(token)).thenReturn(FollowupDraftResult.Drafted(emptyList(), "synthetic-model"))
        mvc.securePost("/w/$token/maker/questions").andExpect {
            status { is3xxRedirection() }; redirectedUrl("/w/$token/maker/questions")
            flash { attribute("questionsDrafted", true) }
        }
        `when`(followups.draft(token)).thenReturn(FollowupDraftResult.Failed(AnalysisFailure.NOT_CONFIGURED))
        mvc.securePost("/w/$token/maker/questions").andExpect {
            status { is3xxRedirection() }
            flash { attribute("questionError", "Question drafting is unavailable here. Existing questions can still be reviewed.") }
        }
    }

    @Test
    fun `fan sees approved choices and answer post stays scoped to its wish`() {
        val question = question(approved = true)
        `when`(followups.fanQuestions(token, wish)).thenReturn(listOf(question))
        `when`(followups.answers(token, wish)).thenReturn(emptyList())
        val html = mvc.get("/w/$token/questions") { param("wish", wish.toString()) }.andExpect {
            status { isOk() }; view { name("followup/fan") }
        }.andReturn().response.contentAsString
        assertContains(html, "Which finish works for you?")
        assertContains(html, "type=\"radio\"")
        assertContains(html, "value=\"$wish\"")
        assertContains(html, "name=\"_csrf\"")
        assertContains(html, "Other conditions may remain Depends")
        assertContains(html, "leave this question unanswered")
        assertContains(html, "not an order. Nothing is charged")
        val answer = FollowupAnswerRecord(workspace, run, option.id, question.id, wish, "matte", now)
        `when`(followups.answer(token, question.id, wish, "matte")).thenReturn(answer)
        mvc.securePost("/w/$token/questions/${question.id}") {
            param("wish", wish.toString()); param("choice", "matte")
        }.andExpect {
            status { is3xxRedirection() }; redirectedUrl("/w/$token/questions?wish=$wish")
            flash { attribute("answerSaved", true) }
        }
        verify(followups).answer(token, question.id, wish, "matte")
        verify(followups, never()).draft(token)
    }

    @Test
    fun `saved fan answer and empty state render without another answer form`() {
        val question = question(approved = true)
        `when`(followups.fanQuestions(token, wish)).thenReturn(listOf(question))
        `when`(followups.answers(token, wish)).thenReturn(listOf(FollowupAnswerRecord(
            workspace, run, option.id, question.id, wish, "matte", now,
        )))
        var html = mvc.get("/w/$token/questions") { param("wish", wish.toString()) }.andReturn().response.contentAsString
        assertContains(html, "Your saved answer")
        assertContains(html, "Source: fan answer")
        assertFalse(html.contains("Save my answer</button>"))
        `when`(followups.fanQuestions(token, wish)).thenReturn(emptyList())
        html = mvc.get("/w/$token/questions") { param("wish", wish.toString()) }.andReturn().response.contentAsString
        assertContains(html, "No questions waiting")
        assertFalse(html.contains("Save my answer</button>"))
    }

    @Test
    fun `question text and choices are escaped in both views`() {
        val question = question(approved = true).apply {
            text = "<script>alert('question')</script>"
            choices = listOf("<b>matte</b>", "glossy")
        }
        `when`(followups.makerPage(token)).thenReturn(FollowupPage(run, option, listOf(question), null))
        `when`(followups.fanQuestions(token, wish)).thenReturn(listOf(question))
        `when`(followups.answers(token, wish)).thenReturn(emptyList())
        val pages = listOf(
            mvc.get("/w/$token/maker/questions").andReturn().response.contentAsString,
            mvc.get("/w/$token/questions") { param("wish", wish.toString()) }.andReturn().response.contentAsString,
        )
        for (html in pages) {
            assertContains(html, "&lt;script&gt;")
            assertContains(html, "&lt;b&gt;matte&lt;/b&gt;")
            assertFalse(html.contains("<script>alert('question')</script>"))
        }
    }

    @Test
    fun `unknown workspace and invalid answers use safe error pages`() {
        `when`(followups.makerPage(token)).thenThrow(WorkspaceNotFoundException())
        mvc.get("/w/$token/maker/questions").andExpect { status { isNotFound() }; view { name("error/not-found") } }
        `when`(followups.fanQuestions(token, wish)).thenThrow(WorkspaceNotFoundException())
        mvc.get("/w/$token/questions") { param("wish", wish.toString()) }.andExpect {
            status { isNotFound() }; view { name("error/not-found") }
        }
        val question = question()
        `when`(followups.answer(token, question.id, wish, "invented")).thenThrow(InvalidFollowupException())
        mvc.securePost("/w/$token/questions/${question.id}") {
            param("wish", wish.toString()); param("choice", "invented")
        }.andExpect {
            status { isBadRequest() }; view { name("followup/invalid") }
            content { string(org.hamcrest.Matchers.containsString("/w/$token/maker/questions")) }
        }
        `when`(followups.answer(token, question.id, wish, "matte")).thenThrow(InvalidWishReviewException())
        mvc.securePost("/w/$token/questions/${question.id}") {
            param("wish", wish.toString()); param("choice", "matte")
        }.andExpect { status { isBadRequest() }; view { name("followup/invalid") } }
    }

    @Test
    fun `all question writes reject missing session csrf before the service`() {
        for (path in listOf("/w/$token/maker/questions", "/w/$token/maker/questions/${UUID.randomUUID()}/approve", "/w/$token/questions/${UUID.randomUUID()}")) {
            mvc.post(path) { param("wish", wish.toString()); param("choice", "matte"); param("text", "A question") }
                .andExpect { status { isForbidden() } }
        }
        verifyNoInteractions(followups)
    }

    @Test
    fun `wish confirmation gives the fan its own return link`() {
        val workspaceToken = workspaces.create().token
        val session = mvc.get("/").andReturn().request.session as MockHttpSession
        val result = mvc.securePost("/w/$workspaceToken", session) { param("text", "Only a matte finish, please") }
            .andExpect { status { is3xxRedirection() } }.andReturn()
        val saved = result.flashMap["submittedWishId"]
        val html = mvc.get("/w/$workspaceToken") { this.session = session }.andReturn().response.contentAsString
        assertContains(html, "/w/$workspaceToken/questions?wish=$saved")
        assertContains(html, "Keep this link to return")
        val returning = mvc.get("/w/$workspaceToken") { this.session = session }.andReturn().response.contentAsString
        assertContains(returning, "/w/$workspaceToken/questions?wish=$saved")
        assertContains(returning, "since your last visit")
        val otherWorkspace = workspaces.create().token
        assertFalse(mvc.get("/w/$otherWorkspace") { this.session = session }
            .andReturn().response.contentAsString.contains("since your last visit"))
    }

    private fun question(approved: Boolean = false) = FollowupQuestion(
        workspace, run, option.id, NeedAttribute.FINISH, "finish", "Which finish works for you?",
        listOf("matte", "glossy"), listOf(wish), now, approved,
    )
}

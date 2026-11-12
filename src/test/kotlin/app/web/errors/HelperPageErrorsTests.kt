package app.web.errors

import app.campaign.replies.InvalidReplyEvidenceException
import app.campaign.replies.ReplyService
import app.campaign.replies.MakerReplies
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertFalse

@SpringBootTest
@AutoConfigureMockMvc
class HelperPageErrorsTests @Autowired constructor(private val mvc: MockMvc) {
    @MockitoBean
    private lateinit var replies: ReplyService

    @Test
    fun `missing parameters render html for browsers and json for api clients`() {
        listOf("questions", "maker/glosses", "maker/replies", "maker/offer-draft").forEach { path ->
            mvc.get("/w/example/$path") { accept = MediaType.TEXT_HTML }.andExpect {
                status { isBadRequest() }; view { name("error/helper") }
            }.andReturn().response.contentAsString.let { assertContains(it, "Request incomplete") }
            mvc.get("/w/example/$path") { accept = MediaType.APPLICATION_JSON }.andExpect {
                status { isBadRequest() }; content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            }
        }
    }

    @Test
    fun `reply page explains when analysis needs refreshing`() {
        val run = UUID.randomUUID()
        `when`(replies.maker("example", run)).thenReturn(MakerReplies(run, "", "REANALYSIS_REQUIRED", emptyList()))
        val html = mvc.get("/w/example/maker/replies?run=$run") { accept = MediaType.TEXT_HTML }.andExpect {
            status { isOk() }; view { name("replies/maker") }
        }.andReturn().response.contentAsString
        assertContains(html, "Review the current analysis before drafting replies")
        assertContains(html, "/w/example/maker/proposal")
        assertFalse(html.contains("Draft replies</button>"))
    }

    @Test
    fun `changed reply evidence requires an explicit new draft`() {
        val run = UUID.randomUUID()
        val latestRun = UUID.randomUUID()
        `when`(replies.maker("example", run)).thenReturn(MakerReplies(latestRun, "fresh-hash", "REFRESH_REQUIRED", emptyList()))
        val html = mvc.get("/w/example/maker/replies?run=$run") { accept = MediaType.TEXT_HTML }.andExpect {
            status { isOk() }; view { name("replies/maker") }
        }.andReturn().response.contentAsString
        assertContains(html, "Earlier drafts and approvals are no longer current")
        assertContains(html, "value=\"$latestRun\"")
        assertContains(html, "fresh-hash")
        assertContains(html, "Draft replies")
    }

    @Test
    fun `stale reply links to latest proposal and retains json for api clients`() {
        val run = UUID.randomUUID()
        `when`(replies.maker("example", run)).thenThrow(InvalidReplyEvidenceException())
        val path = "/w/example/maker/replies?run=$run"
        val html = mvc.get(path) { accept = MediaType.TEXT_HTML }.andExpect {
            status { isConflict() }; view { name("replies/stale") }
        }.andReturn().response.contentAsString
        assertContains(html, "/w/example/maker/proposal")
        mvc.get(path) { accept = MediaType.APPLICATION_JSON }.andExpect {
            status { isConflict() }; content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
        }
    }
}

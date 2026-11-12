package app.web.security

import app.campaign.workspace.WorkspaceService
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.server.autoconfigure.ServerProperties
import org.springframework.boot.web.server.servlet.Session
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.Base64
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
class WebSecurityIntegrationTests @Autowired constructor(
    private val mvc: MockMvc,
    private val workspaces: WorkspaceService,
    private val serverProperties: ServerProperties,
) {
    @Test
    fun `session tracking uses cookies only so session ids cannot be rewritten into urls`() {
        assertEquals(setOf(Session.SessionTrackingMode.COOKIE), serverProperties.servlet.session.trackingModes)
    }

    @Test
    fun `rendered post forms submit with automatic session csrf while unverified actions are rejected`() {
        val page = mvc.get("/").andExpect { status { isOk() } }.andReturn()
        val csrf = page.request.getAttribute("_csrf") as CsrfToken
        assertContains(page.response.contentAsString, "name=\"_csrf\" value=\"${csrf.token}\"")
        assertContains(page.response.contentAsString, "name=\"_csrf\" content=\"${csrf.token}\"")
        assertContains(page.response.contentAsString, "name=\"_csrf_header\" content=\"${csrf.headerName}\"")
        val session = page.request.session as MockHttpSession
        mvc.post("/demo").andExpect { status { isForbidden() } }
        val created = mvc.post("/demo") {
            this.session = session
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            content = "_csrf=${csrf.token}"
        }.andExpect { status { is3xxRedirection() } }.andReturn()
        val token = created.response.redirectedUrl!!.split('/')[2]
        val wishPage = mvc.get("/w/$token") { this.session = session }.andReturn().response.contentAsString
        assertContains(wishPage, "name=\"_csrf\" value=\"${csrf.token}\"")
        mvc.post("/w/$token") {
            this.session = session
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            content = "_csrf=${csrf.token}&text=A+small+print&displayName=Synthetic+fan"
        }.andExpect { status { is3xxRedirection() } }
        assertEquals("Synthetic fan", workspaces.listWishes(token).last { it.text == "A small print" }.displayName)
        mvc.post("/w/$token/api/pledges") {
            this.session = session
            contentType = MediaType.APPLICATION_JSON
            content = "{\"quantity\":1}"
        }.andExpect { status { isForbidden() } }
        mvc.post("/w/$token/api/pledges") {
            this.session = session
            header(csrf.headerName, csrf.token)
            contentType = MediaType.APPLICATION_JSON
            content = "{\"quantity\":1}"
        }.andExpect { status { isConflict() } }
    }

    @Test
    fun `workspace and csrf bearer values contain 256 random bits and differ across sessions`() {
        val first = mvc.get("/").andReturn()
        val second = mvc.get("/").andReturn()
        val tokens = listOf(
            workspaces.create().token, workspaces.create().token,
            (first.request.getAttribute("_csrf") as CsrfToken).token,
            (second.request.getAttribute("_csrf") as CsrfToken).token,
        )
        assertEquals(tokens.size, tokens.toSet().size)
        tokens.forEach {
            assertTrue(it.matches(Regex("[A-Za-z0-9_-]{43}")))
            assertEquals(32, Base64.getUrlDecoder().decode(it).size)
        }
        assertNotEquals(first.request.session!!.id, second.request.session!!.id)
    }

    @Test
    fun `bad requests do not log or reflect tokens buyer details or request bodies`() {
        val token = workspaces.create().token
        val marker = "synthetic-buyer@example.invalid"
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val events = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(events)
        try {
            val malformed = mvc.securePost("/w/$token/api/pledges") {
                contentType = MediaType.APPLICATION_JSON
                content = "{\"quantity\":\"$marker\",\"shipping\":\"Synthetic Private Address\"}"
            }.andExpect { status { isBadRequest() } }.andReturn()
            val invalidParameter = mvc.get("/w/$token/maker/proposal") {
                param("run", marker)
            }.andExpect { status { isBadRequest() } }.andReturn()
            val rejected = mvc.post("/w/$token/api/pledges") {
                contentType = MediaType.APPLICATION_JSON
                content = "{\"buyer\":\"$marker\"}"
            }.andExpect { status { isForbidden() } }.andReturn()
            val output = events.list.joinToString("\n") { it.formattedMessage + it.throwableProxy?.message.orEmpty() } +
                malformed.response.contentAsString + invalidParameter.response.contentAsString + rejected.response.contentAsString
            for (privateValue in listOf(token, marker, "Synthetic Private Address")) assertFalse(output.contains(privateValue))
        } finally {
            root.detachAppender(events)
            events.stop()
        }
    }
}

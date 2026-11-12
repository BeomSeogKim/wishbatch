package app.web.operations

import app.campaign.port.PaymentGateway
import app.campaign.port.WishAnalyzer
import app.campaign.workspace.WorkspaceService
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import jakarta.servlet.DispatcherType
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestComponent
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(OperationsIntegrationTests.Fixtures::class)
class OperationsIntegrationTests @Autowired constructor(private val mvc: MockMvc) {
    @MockitoBean
    private lateinit var payments: PaymentGateway

    @MockitoBean
    private lateinit var analyzer: WishAnalyzer

    @MockitoBean
    private lateinit var workspaces: WorkspaceService

    @Test
    fun `health reports only liveness without creating workspaces or calling providers`() {
        val response = mvc.get("/health").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            content { json("""{"liveness":"up"}""", true) }
            header { string("X-Content-Type-Options", "nosniff") }
            header { string("X-Frame-Options", "DENY") }
            header { string("Cache-Control", "no-store") }
        }.andReturn().response
        assertEquals("{\"liveness\":\"up\"}", response.contentAsString)
        verifyNoInteractions(payments, analyzer, workspaces)
    }

    @Test
    fun `server IDs ignore inbound headers and correlate controller MDC and response`() {
        val first = mvc.get("/operations/ok") {
            header("X-Request-ID", "synthetic-untrusted-id\r\nforged-log")
        }.andExpect { status { isOk() } }.andReturn()
        val id = assertNotNull(first.response.getHeader("X-Request-ID"))
        assertEquals(id, UUID.fromString(id).toString())
        assertEquals(id, first.request.getAttribute("app.requestId"))
        assertEquals(id, first.response.contentAsString)
        val second = mvc.get("/operations/ok").andReturn()
        assertNotEquals(id, second.response.getHeader("X-Request-ID"))
        assertNull(MDC.get("requestId"))
    }

    @Test
    fun `security rejection and existing bad request JSON keep their status and receive IDs`() {
        val rejected = mvc.post("/operations/ok").andExpect {
            status { isForbidden() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
        }.andReturn()
        val oversized = mvc.post("/operations/ok") {
            content = ByteArray(512 * 1024 + 1)
        }.andExpect { status { isPayloadTooLarge() } }.andReturn()
        val invalid = mvc.get("/operations/number") {
            param("value", "synthetic-private-buyer")
        }.andExpect {
            status { isBadRequest() }
            content { json("""{"message":"This request could not be read. Check the form and try again."}""") }
        }.andReturn()
        val conflict = mvc.get("/operations/conflict").andExpect { status { isConflict() } }.andReturn()
        for (result in listOf(rejected, oversized, invalid, conflict)) {
            assertEquals(result.request.getAttribute("app.requestId"), result.response.getHeader("X-Request-ID"))
            assertNotNull(result.response.getHeader("X-Request-ID"))
        }
    }

    @Test
    fun `failing controller retains its ID through explicit framework error dispatch without input in task logs`() {
        val logger = LoggerFactory.getLogger(RequestCorrelationFilter::class.java) as Logger
        val events = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(events)
        val privateText = "synthetic-buyer-token-request-sentinel"
        MDC.put("requestId", "previous-context")
        try {
            var failedRequest: HttpServletRequest? = null
            assertFailsWith<ServletException> {
                mvc.get("/operations/fail/$privateText") {
                    param("message", privateText)
                    header("X-Request-ID", privateText)
                    header("Authorization", privateText)
                    with { request -> request.also { failedRequest = it } }
                }
            }
            assertEquals("previous-context", MDC.get("requestId"))
            val request = assertNotNull(failedRequest)
            val id = assertNotNull(request.getAttribute("app.requestId") as? String)
            assertTrue(events.list.isEmpty())

            // MockMvc does not perform the servlet container's automatic error redispatch.
            val error = mvc.perform {
                (request as org.springframework.mock.web.MockHttpServletRequest).apply {
                    dispatcherType = DispatcherType.ERROR
                    requestURI = "/error"
                    servletPath = "/error"
                    setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500)
                    addHeader("Accept", "application/json")
                }
            }.andReturn()
            assertEquals(500, error.response.status)
            assertEquals(id, error.response.getHeader("X-Request-ID"))
            assertEquals("previous-context", MDC.get("requestId"))
            val event = events.list.single()
            assertEquals(id, event.mdcPropertyMap["requestId"])
            assertTrue(event.formattedMessage.matches(Regex(
                "request_completed requestId=$id method=GET status=500 elapsedMs=\\d+",
            )))
            val output = event.formattedMessage + event.throwableProxy?.message.orEmpty()
            assertFalse(output.contains(privateText))
            assertFalse(output.contains("/operations"))
            assertNull(event.throwableProxy)
        } finally {
            MDC.remove("requestId")
            logger.detachAppender(events)
            events.stop()
        }
        mvc.get("/operations/ok").andExpect { status { isOk() } }
        assertNull(MDC.get("requestId"))
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Fixtures {
        @Bean
        fun operationsFixture() = OperationsFixture()
    }
}

@TestComponent
@RestController
class OperationsFixture {
    @GetMapping("/operations/ok")
    fun ok(request: HttpServletRequest): String {
        assertEquals(request.getAttribute("app.requestId"), MDC.get("requestId"))
        return MDC.get("requestId")
    }

    @GetMapping("/operations/number")
    fun number(@RequestParam value: Int) = value

    @GetMapping("/operations/conflict")
    fun conflict(): ResponseEntity<String> = ResponseEntity.status(409).body("Conflict")

    @GetMapping("/operations/fail/{value}")
    fun fail(@RequestParam message: String): String = throw IllegalStateException(message)
}

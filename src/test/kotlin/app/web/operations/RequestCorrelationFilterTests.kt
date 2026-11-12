package app.web.operations

import app.web.security.CsrfToken
import app.web.security.WebSecurityFilter
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import jakarta.servlet.DispatcherType
import jakarta.servlet.ServletException
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class RequestCorrelationFilterTests {
    @Test
    fun `normal and throwing chains restore previous MDC without altering exceptions`() {
        val filter = RequestCorrelationFilter()
        val failure = ServletException("synthetic-private-filter-message")
        for (previous in listOf(null, "previous-request-context")) {
            if (previous == null) MDC.remove("requestId") else MDC.put("requestId", previous)
            try {
                val normal = MockHttpServletRequest("GET", "/ok")
                filter.doFilter(normal, MockHttpServletResponse()) { request, _ ->
                    assertEquals(request.getAttribute("app.requestId"), MDC.get("requestId"))
                    MDC.put("unrelated", "preserved-context")
                }
                assertEquals(previous, MDC.get("requestId"))
                assertEquals("preserved-context", MDC.get("unrelated"))
                val failed = MockHttpServletRequest("GET", "/fail")
                val response = MockHttpServletResponse()
                assertSame(failure, assertFailsWith<ServletException> {
                    filter.doFilter(failed, response) { request, _ ->
                        assertEquals(request.getAttribute("app.requestId"), MDC.get("requestId"))
                        throw failure
                    }
                })
                assertEquals(previous, MDC.get("requestId"))
                assertNotNull(response.getHeader("X-Request-ID"))
                assertNotEquals(normal.getAttribute("app.requestId"), failed.getAttribute("app.requestId"))
            } finally {
                MDC.remove("requestId")
                MDC.remove("unrelated")
            }
        }
    }

    @Test
    fun `nested error dispatch restores MDC and logs one completion with the same ID`() {
        val logger = LoggerFactory.getLogger(RequestCorrelationFilter::class.java) as Logger
        val events = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(events)
        try {
            val filter = RequestCorrelationFilter()
            val request = MockHttpServletRequest("GET", "/missing")
            val response = MockHttpServletResponse()
            filter.doFilter(request, response) { _, _ ->
                val id = MDC.get("requestId")
                request.dispatcherType = DispatcherType.ERROR
                response.reset()
                filter.doFilter(request, response) { incoming, outgoing ->
                    assertEquals(id, incoming.getAttribute("app.requestId"))
                    assertEquals(id, MDC.get("requestId"))
                    (outgoing as MockHttpServletResponse).status = 404
                }
                assertEquals(id, MDC.get("requestId"))
            }
            assertNull(MDC.get("requestId"))
            assertEquals(request.getAttribute("app.requestId"), response.getHeader("X-Request-ID"))
            assertEquals(1, events.list.size)
            assertEquals("404", Regex("status=(\\d+)").find(events.list.single().formattedMessage)!!.groupValues[1])
        } finally {
            logger.detachAppender(events)
            events.stop()
        }
    }

    @Test
    fun `rate limited requests retain security JSON and opaque correlation without logging submitted values`() {
        val filter = RequestCorrelationFilter()
        val security = WebSecurityFilter()
        val page = MockHttpServletRequest("GET", "/health")
        security.doFilter(page, MockHttpServletResponse()) { _, _ -> }
        val session = page.session as MockHttpSession
        val csrf = page.getAttribute("_csrf") as CsrfToken
        val logger = LoggerFactory.getLogger(RequestCorrelationFilter::class.java) as Logger
        val events = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(events)
        try {
            val sentinel = "synthetic-buyer-token-request-body"
            repeat(6) { index ->
                val request = MockHttpServletRequest("POST", "/demo").apply {
                    servletPath = "/demo"
                    this.session = session
                    addHeader(csrf.headerName, csrf.token)
                    addHeader("X-Request-ID", sentinel)
                    queryString = "private=$sentinel"
                    setContent(sentinel.toByteArray())
                }
                val response = MockHttpServletResponse()
                var called = false
                filter.doFilter(request, response) { incoming, outgoing ->
                    security.doFilter(incoming, outgoing) { _, _ -> called = true }
                }
                assertEquals(if (index == 5) 429 else 200, response.status)
                assertEquals(index != 5, called)
                assertEquals(request.getAttribute("app.requestId"), response.getHeader("X-Request-ID"))
                assertNotEquals(sentinel, response.getHeader("X-Request-ID"))
                if (index == 5) {
                    assertEquals("600", response.getHeader("Retry-After"))
                    assertEquals("{\"message\":\"Too many requests. Wait before trying again.\"}", response.contentAsString)
                }
            }
            assertEquals(6, events.list.size)
            assertFalse(events.list.joinToString { it.formattedMessage + it.throwableProxy?.message.orEmpty() }.contains(sentinel))
            assertNull(MDC.get("requestId"))
        } finally {
            logger.detachAppender(events)
            events.stop()
        }
    }
}

package app.web.security

import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import java.util.Base64
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebSecurityFilterTests {
    @Test
    fun `known and unknown length bodies over the cap are rejected before dispatch`() {
        for (unknownLength in listOf(false, true)) {
            val request = if (unknownLength) object : MockHttpServletRequest("POST", "/demo") {
                override fun getContentLength(): Int = -1
                override fun getContentLengthLong(): Long = -1
            } else request("POST", "/demo")
            request.setContent(ByteArray(WebSecurityFilter.MAX_BODY_BYTES + 1))
            if (unknownLength) request.addHeader("Transfer-Encoding", "chunked")
            val result = dispatch(WebSecurityFilter(), request)
            assertEquals(413, result.response.status)
            assertFalse(result.called)
            assertContains(result.response.contentAsString, "too large")
        }
    }

    @Test
    fun `body exactly at the cap remains readable by downstream handlers`() {
        val filter = WebSecurityFilter()
        val session = session(filter)
        val request = authorized("POST", "/upload", session)
        val body = ByteArray(WebSecurityFilter.MAX_BODY_BYTES) { 65 }
        request.setContent(body)
        val result = dispatch(filter, request)
        assertTrue(result.called)
        assertEquals(body.toList(), result.request!!.inputStream.readAllBytes().toList())
    }

    @Test
    fun `bounded forms decode repeated fields and preserve query parameters and raw body`() {
        val original = request("POST", "/form")
        original.contentType = "application/x-www-form-urlencoded;charset=UTF-8"
        original.queryString = "query=keep"
        original.addParameter("query", "keep")
        val body = "name=Fern+Fan&tag=one&tag=two&empty&text=%3Cscript%3E&utf8=%C3%A9".toByteArray()
        val bounded = BoundedRequest(original, body)
        assertEquals("keep", bounded.getParameter("query"))
        assertEquals("Fern Fan", bounded.getParameter("name"))
        assertEquals(listOf("one", "two"), bounded.getParameterValues("tag")!!.toList())
        assertEquals("", bounded.getParameter("empty"))
        assertEquals("<script>", bounded.getParameter("text"))
        assertEquals("\u00e9", bounded.getParameter("utf8"))
        assertNull(bounded.getParameter("missing"))
        assertEquals(setOf("query", "name", "tag", "empty", "text", "utf8"), bounded.parameterNames.toList().toSet())
        assertEquals(body.toList(), bounded.inputStream.readAllBytes().toList())
        assertEquals(body.toString(Charsets.UTF_8), bounded.reader.readText())
    }

    @Test
    fun `every unsafe method rejects missing tokens and accepts the matching session token`() {
        val filter = WebSecurityFilter()
        val session = session(filter)
        for (method in listOf("POST", "PUT", "PATCH", "DELETE", "CUSTOM")) {
            assertEquals(403, dispatch(filter, request(method, "/mutation")).response.status)
            val missing = request(method, "/mutation").apply { this.session = session }
            assertEquals(403, dispatch(filter, missing).response.status)
            assertTrue(dispatch(filter, authorized(method, "/mutation", session)).called)
        }
    }

    @Test
    fun `forms accept hidden tokens and JSON requires the matching header`() {
        val filter = WebSecurityFilter()
        val session = session(filter)
        val csrf = token(session)
        val form = request("POST", "/form").apply {
            this.session = session
            contentType = "application/x-www-form-urlencoded"
            setContent("_csrf=${csrf.token}&text=A+wish".toByteArray())
        }
        val accepted = dispatch(filter, form)
        assertTrue(accepted.called)
        assertEquals("A wish", accepted.request!!.getParameter("text"))
        val json = authorized("POST", "/json", session).apply {
            contentType = "application/json"
            setContent("{\"quantity\":1}".toByteArray())
        }
        assertTrue(dispatch(filter, json).called)
        for (supplied in listOf(null, "wrong")) {
            val rejected = request("POST", "/json").apply {
                this.session = session
                contentType = "application/json"
                setContent("{\"_csrf\":\"${csrf.token}\"}".toByteArray())
                if (supplied != null) addHeader(csrf.headerName, supplied)
            }
            assertEquals(403, dispatch(filter, rejected).response.status)
        }
    }

    @Test
    fun `malformed encoded forms receive a generic bad request without dispatch`() {
        val filter = WebSecurityFilter()
        val session = session(filter)
        for (body in listOf("_csrf=%", "_csrf=%GG", "text=%E&other=%")) {
            val request = authorized("POST", "/form", session).apply {
                contentType = "application/x-www-form-urlencoded"
                setContent(body.toByteArray())
            }
            val result = dispatch(filter, request)
            assertEquals(400, result.response.status)
            assertFalse(result.called)
            assertFalse(result.response.contentAsString.contains(body))
        }
    }

    @Test
    fun `tokens from another session and cross site requests are rejected`() {
        val filter = WebSecurityFilter()
        val first = session(filter)
        val second = session(filter)
        val foreign = authorized("POST", "/mutation", first).apply { this.session = second }
        assertEquals(403, dispatch(filter, foreign).response.status)
        val crossSite = authorized("POST", "/mutation", first).apply { addHeader("Sec-Fetch-Site", "cross-site") }
        assertEquals(403, dispatch(filter, crossSite).response.status)
        assertTrue(dispatch(filter, authorized("POST", "/mutation", first).apply {
            addHeader("Sec-Fetch-Site", "same-origin")
        }).called)
    }

    @Test
    fun `only the exact POST webhook path is exempt from browser CSRF`() {
        val filter = WebSecurityFilter()
        assertTrue(dispatch(filter, request("POST", "/webhooks/paypal")).called)
        for (path in listOf("/webhooks/paypal/", "/webhooks/paypal/other", "/webhooks/other")) {
            assertEquals(403, dispatch(filter, request("POST", path)).response.status)
        }
        for (method in listOf("PUT", "PATCH", "DELETE")) {
            assertEquals(403, dispatch(filter, request(method, "/webhooks/paypal")).response.status)
        }
    }

    @Test
    fun `security headers allow sandbox checkout and HSTS is limited to secure requests`() {
        for (secure in listOf(false, true)) {
            val response = dispatch(WebSecurityFilter(), request("GET", "/").apply { isSecure = secure }).response
            assertEquals("nosniff", response.getHeader("X-Content-Type-Options"))
            assertEquals("DENY", response.getHeader("X-Frame-Options"))
            assertEquals("no-referrer", response.getHeader("Referrer-Policy"))
            assertEquals("no-store", response.getHeader("Cache-Control"))
            assertEquals("camera=(), microphone=(), geolocation=()", response.getHeader("Permissions-Policy"))
            val csp = assertNotNull(response.getHeader("Content-Security-Policy"))
            for (directive in listOf("script-src", "connect-src", "frame-src")) {
                val value = csp.split(';').single { it.trim().startsWith("$directive ") }
                assertContains(value, "https://*.paypal.com")
                assertContains(value, "https://*.paypalobjects.com")
            }
            assertContains(csp, "object-src 'none'")
            assertContains(csp, "frame-ancestors 'none'")
            assertEquals(if (secure) "max-age=31536000" else null, response.getHeader("Strict-Transport-Security"))
        }
        val rejected = dispatch(WebSecurityFilter(), request("POST", "/demo")).response
        assertNotNull(rejected.getHeader("Content-Security-Policy"))
    }

    @Test
    fun `demo and analysis limits use the remote address rather than forwarding headers`() {
        val filter = WebSecurityFilter()
        val session = session(filter)
        for ((path, limit, retry) in listOf(Triple("/demo", 5, "600"), Triple("/w/example/maker/proposal", 3, "60"))) {
            repeat(limit) { index ->
                val request = authorized("POST", path, session).apply {
                    remoteAddr = "192.0.2.1"
                    addHeader("X-Forwarded-For", "198.51.100.$index")
                }
                assertTrue(dispatch(filter, request).called)
            }
            val limited = dispatch(filter, authorized("POST", path, session).apply {
                remoteAddr = "192.0.2.1"
                addHeader("X-Forwarded-For", "203.0.113.1")
            })
            assertEquals(429, limited.response.status)
            assertEquals(retry, limited.response.getHeader("Retry-After"))
            assertFalse(limited.called)
            assertEquals(429, dispatch(filter, authorized("POST", "$path;ignored=value", session).apply {
                remoteAddr = "192.0.2.1"
            }).response.status)
            assertTrue(dispatch(filter, authorized("POST", path, session).apply { remoteAddr = "192.0.2.2" }).called)
        }
    }

    @Test
    fun `session CSRF tokens contain 256 random bits remain stable and differ across sessions`() {
        val filter = WebSecurityFilter()
        val first = session(filter)
        val second = session(filter)
        assertNotEquals(token(first), token(second))
        assertEquals(32, Base64.getUrlDecoder().decode(token(first).token).size)
        assertTrue(token(first).token.matches(Regex("[A-Za-z0-9_-]{43}")))
        val reloaded = dispatch(filter, request("GET", "/").apply { session = first })
        assertEquals(token(first), reloaded.request!!.getAttribute("_csrf"))
        val static = dispatch(filter, request("GET", "/js/pledge.js"))
        assertNull(static.request!!.getSession(false))
    }

    @Test
    fun `form processor adds hidden tokens to POST forms and omits them from GET forms`() {
        val request = request("GET", "/")
        val csrf = CsrfToken("synthetic-token")
        request.setAttribute("_csrf", csrf)
        val processor = CsrfFormProcessor()
        assertEquals("/demo", processor.processAction(request, "/demo", "post"))
        assertEquals(mapOf("_csrf" to csrf.token), processor.getExtraHiddenFields(request))
        processor.processAction(request, "/search", "get")
        assertTrue(processor.getExtraHiddenFields(request).isEmpty())
        assertEquals("unchanged", processor.processFormFieldValue(request, "name", "unchanged", "text"))
        assertEquals("/w/example", processor.processUrl(request, "/w/example"))
    }

    private fun request(method: String, path: String) = MockHttpServletRequest(method, path).apply { servletPath = path }

    private fun token(session: MockHttpSession) = session.getAttribute(WebSecurityFilter.SESSION_TOKEN) as CsrfToken

    private fun session(filter: WebSecurityFilter): MockHttpSession =
        dispatch(filter, request("GET", "/")).request!!.session as MockHttpSession

    private fun authorized(method: String, path: String, session: MockHttpSession) = request(method, path).apply {
        this.session = session
        addHeader(token(session).headerName, token(session).token)
    }

    private data class Dispatch(val response: MockHttpServletResponse, val called: Boolean, val request: HttpServletRequest?)

    private fun dispatch(filter: WebSecurityFilter, request: MockHttpServletRequest): Dispatch {
        val response = MockHttpServletResponse()
        var downstream: HttpServletRequest? = null
        filter.doFilter(request, response) { incoming, _ -> downstream = incoming as HttpServletRequest }
        return Dispatch(response, downstream != null, downstream)
    }
}

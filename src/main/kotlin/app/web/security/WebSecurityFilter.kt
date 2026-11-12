package app.web.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

class WebSecurityFilter : OncePerRequestFilter() {
    private val random = SecureRandom()
    private val demos = RequestRateLimiter(5, Duration.ofMinutes(10))
    private val analyses = RequestRateLimiter(3, Duration.ofMinutes(1))
    private val analysisPath = Regex("/w/[^/]+/maker/(proposal|analysis-progress)")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("X-Frame-Options", "DENY")
        response.setHeader("Referrer-Policy", "no-referrer")
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        response.setHeader("Content-Security-Policy", CSP)
        response.setHeader("Cache-Control", "no-store")
        if (request.isSecure) response.setHeader("Strict-Transport-Security", "max-age=31536000")

        val unsafe = request.method !in SAFE_METHODS
        val path = request.servletPath.ifEmpty { request.requestURI.removePrefix(request.contextPath) }
        if (request.contentLengthLong > MAX_BODY_BYTES) {
            reject(response, 413, "This request is too large. Submit at most 512 KiB.")
            return
        }
        val bounded = if (unsafe || request.contentLengthLong != 0L) {
            val body = request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
            if (body.size > MAX_BODY_BYTES) {
                reject(response, 413, "This request is too large. Submit at most 512 KiB.")
                return
            }
            BoundedRequest(request, body)
        } else request

        if (unsafe) {
            try {
                bounded.parameterMap
            } catch (_: IllegalArgumentException) {
                reject(response, 400, "This request could not be read. Reload the page and try again.")
                return
            }
        }

        // The webhook authenticates the raw body with a provider signature before any mutation.
        if (request.method == "POST" && path == "/webhooks/paypal") {
            chain.doFilter(bounded, response)
            return
        }

        if (unsafe) {
            val expected = request.getSession(false)?.getAttribute(SESSION_TOKEN) as? CsrfToken
            val supplied = bounded.getHeader("X-CSRF-TOKEN") ?: bounded.getParameter("_csrf")
            if (request.getHeader("Sec-Fetch-Site") == "cross-site" || expected == null || supplied == null ||
                !MessageDigest.isEqual(expected.token.toByteArray(), supplied.toByteArray())) {
                reject(response, 403, "This page expired or the request could not be verified. Reload the page and try again.")
                return
            }
            bounded.setAttribute("_csrf", expected)
            val route = path.split('/').joinToString("/") { it.substringBefore(';') }
            val allowed = when {
                request.method == "POST" && route == "/demo" -> demos.allow(request.remoteAddr)
                request.method == "POST" && analysisPath.matches(route) -> analyses.allow(request.remoteAddr)
                else -> true
            }
            if (!allowed) {
                response.setHeader("Retry-After", if (route == "/demo") "600" else "60")
                reject(response, 429, "Too many requests. Wait before trying again.")
                return
            }
        } else if (!request.requestURI.removePrefix(request.contextPath).let {
                it.startsWith("/css/") || it.startsWith("/js/") || it == "/favicon.svg"
            }) {
            val session = request.getSession(true)
            val csrf = synchronized(session) {
                session.getAttribute(SESSION_TOKEN) as? CsrfToken ?: CsrfToken(
                    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes)),
                ).also { session.setAttribute(SESSION_TOKEN, it) }
            }
            bounded.setAttribute("_csrf", csrf)
        }
        chain.doFilter(bounded, response)
    }

    private fun reject(response: HttpServletResponse, status: Int, message: String) {
        response.status = status
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        response.writer.write("{\"message\":\"$message\"}")
    }

    companion object {
        const val MAX_BODY_BYTES = 512 * 1024
        internal const val SESSION_TOKEN = "app.web.security.csrf"
        internal val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS", "TRACE")
        // Inline scripts and styles are used by the server-rendered pages and PayPal SDK.
        internal const val CSP = "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; " +
            "form-action 'self'; script-src 'self' 'unsafe-inline' https://*.paypal.com https://*.paypalobjects.com " +
            "https://cdn.jsdelivr.net/npm/ag-grid-community@36.3.0/dist/ag-grid-community.min.noStyle.js " +
            "https://cdn.jsdelivr.net/npm/ag-charts-community@14.3.0/dist/umd/ag-charts-community.min.js; " +
            "style-src 'self' 'unsafe-inline'; img-src 'self' data: https://*.paypal.com https://*.paypalobjects.com; " +
            "connect-src 'self' https://*.paypal.com https://*.paypalobjects.com; " +
            "frame-src https://*.paypal.com https://*.paypalobjects.com"
    }
}

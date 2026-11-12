package app.web.operations

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.web.filter.GenericFilterBean
import java.util.UUID

class RequestCorrelationFilter : GenericFilterBean() {
    private val log = LoggerFactory.getLogger(RequestCorrelationFilter::class.java)

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val httpRequest = request as HttpServletRequest
        val httpResponse = response as HttpServletResponse
        val state = request.getAttribute(STATE_ATTRIBUTE) as? RequestState ?: RequestState().also {
            request.setAttribute(STATE_ATTRIBUTE, it)
        }
        request.setAttribute(REQUEST_ID_ATTRIBUTE, state.id)
        httpResponse.setHeader(RESPONSE_HEADER, state.id)
        val previous = MDC.get(MDC_KEY)
        MDC.put(MDC_KEY, state.id)
        try {
            chain.doFilter(request, response)
            // Escaping exceptions are completed by the container's error dispatch.
            if (!state.logged) {
                state.logged = true
                log.info(
                    "request_completed requestId={} method={} status={} elapsedMs={}",
                    state.id, httpRequest.method, httpResponse.status,
                    (System.nanoTime() - state.startedAt) / 1_000_000,
                )
            }
        } finally {
            if (previous == null) MDC.remove(MDC_KEY) else MDC.put(MDC_KEY, previous)
        }
    }

    private class RequestState(
        val id: String = UUID.randomUUID().toString(),
        val startedAt: Long = System.nanoTime(),
        var logged: Boolean = false,
    )

    companion object {
        const val REQUEST_ID_ATTRIBUTE = "app.requestId"
        const val RESPONSE_HEADER = "X-Request-ID"
        const val MDC_KEY = "requestId"
        private const val STATE_ATTRIBUTE = "app.web.operations.correlation"
    }
}

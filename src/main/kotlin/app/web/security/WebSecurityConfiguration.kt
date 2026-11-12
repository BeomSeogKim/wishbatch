package app.web.security

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.servlet.support.RequestDataValueProcessor

data class CsrfToken(
    val token: String,
    val headerName: String = "X-CSRF-TOKEN",
    val parameterName: String = "_csrf",
)

@Configuration(proxyBeanMethods = false)
class WebSecurityConfiguration {
    @Bean
    fun webSecurityFilter() = FilterRegistrationBean(WebSecurityFilter()).apply {
        order = Ordered.HIGHEST_PRECEDENCE + 10
    }

    @Bean
    fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfFormProcessor()
}

@ControllerAdvice
class CsrfModel {
    @ModelAttribute("_csrf")
    fun csrf(request: HttpServletRequest): CsrfToken? = request.getAttribute("_csrf") as? CsrfToken
}

class CsrfFormProcessor : RequestDataValueProcessor {
    override fun processAction(request: HttpServletRequest, action: String, httpMethod: String): String {
        request.setAttribute(FORM_METHOD, httpMethod)
        return action
    }

    override fun processFormFieldValue(request: HttpServletRequest, name: String?, value: String, type: String) = value

    override fun getExtraHiddenFields(request: HttpServletRequest): Map<String, String> {
        if (request.getAttribute(FORM_METHOD)?.toString()?.uppercase() in WebSecurityFilter.SAFE_METHODS) return emptyMap()
        val csrf = request.getAttribute("_csrf") as? CsrfToken ?: return emptyMap()
        return mapOf(csrf.parameterName to csrf.token)
    }

    override fun processUrl(request: HttpServletRequest, url: String) = url

    private companion object {
        const val FORM_METHOD = "app.web.security.formMethod"
    }
}

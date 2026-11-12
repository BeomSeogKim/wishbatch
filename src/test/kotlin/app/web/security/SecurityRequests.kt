package app.web.security

import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

fun MockMvc.securePost(
    path: String,
    dsl: MockHttpServletRequestDsl.() -> Unit = {},
): ResultActionsDsl {
    val page = get("/").andReturn()
    val csrf = page.request.getAttribute("_csrf") as CsrfToken
    val csrfSession = page.request.session as MockHttpSession
    return post(path) {
        session = csrfSession
        header(csrf.headerName, csrf.token)
        with { request ->
            request.remoteAddr = UUID.randomUUID().toString()
            request
        }
        dsl()
    }
}

package app.web.security

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.BindException
import org.springframework.web.bind.ServletRequestBindingException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class PrivateRequestErrors {
    // Default binding diagnostics can include the submitted value in application logs.
    @ExceptionHandler(
        MethodArgumentTypeMismatchException::class,
        ServletRequestBindingException::class,
        BindException::class,
        HttpMessageNotReadableException::class,
    )
    fun invalidRequest(): ResponseEntity<Map<String, String>> = ResponseEntity.badRequest().body(
        mapOf("message" to "This request could not be read. Check the form and try again."),
    )
}

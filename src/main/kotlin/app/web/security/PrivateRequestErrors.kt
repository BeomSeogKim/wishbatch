package app.web.security

import app.web.errors.wantsHtml
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.BindException
import org.springframework.web.bind.ServletRequestBindingException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.ModelAndView

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
    fun invalidRequest(request: HttpServletRequest): Any {
        val handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE) as? HandlerMethod
        val responseBody = handler == null || handler.hasMethodAnnotation(ResponseBody::class.java) ||
            AnnotatedElementUtils.hasAnnotation(handler.beanType, ResponseBody::class.java) ||
            ResponseEntity::class.java.isAssignableFrom(handler.method.returnType)
        if (!responseBody && wantsHtml(request)) {
            return ModelAndView("error/4xx", mapOf("demoGuide" to null), HttpStatus.BAD_REQUEST)
        }
        return ResponseEntity.badRequest().body(mapOf("message" to "This request could not be read. Check the form and try again."))
    }
}

package app.web.errors

import app.web.followup.FollowupController
import app.web.gloss.WishGlossController
import app.web.offerdraft.OfferDraftController
import app.web.replies.ReplyController
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.BindException
import org.springframework.web.bind.ServletRequestBindingException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.ModelAndView

@ControllerAdvice(assignableTypes = [FollowupController::class, WishGlossController::class, ReplyController::class, OfferDraftController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class HelperPageErrors {
    @ExceptionHandler(
        MethodArgumentTypeMismatchException::class,
        ServletRequestBindingException::class,
        BindException::class,
        HttpMessageNotReadableException::class,
    )
    fun invalidRequest(request: HttpServletRequest): Any {
        if (!wantsHtml(request)) {
            return ResponseEntity.badRequest().body(mapOf("message" to "This request could not be read. Check the form and try again."))
        }
        val variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<*, *>
        return ModelAndView("error/helper", mapOf("token" to variables?.get("token")), org.springframework.http.HttpStatus.BAD_REQUEST)
    }
}

fun wantsHtml(request: HttpServletRequest): Boolean = try {
    MediaType.parseMediaTypes(request.getHeader("Accept") ?: "").any {
        it.type == "text" && it.subtype == "html" && it.qualityValue > 0
    }
} catch (_: IllegalArgumentException) {
    false
}

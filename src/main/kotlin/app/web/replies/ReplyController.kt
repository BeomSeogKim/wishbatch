package app.web.replies

import app.campaign.replies.InvalidReplyEvidenceException
import app.campaign.replies.ReplyService
import app.campaign.replies.UnsafeReplyException
import app.campaign.workspace.WorkspaceNotFoundException
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletRequest
import app.web.errors.wantsHtml
import org.springframework.http.ResponseEntity
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.ModelAndView
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

@Controller
class ReplyController(private val replies: ReplyService) {
    @GetMapping("/w/{token}/maker/replies")
    fun maker(@PathVariable token: String, @RequestParam run: UUID, model: Model): String {
        model.addAttribute("token", token)
        model.addAttribute("replies", replies.maker(token, run))
        return "replies/maker"
    }

    @PostMapping("/w/{token}/maker/replies/generate")
    fun generate(@PathVariable token: String, @RequestParam run: UUID, @RequestParam evidenceHash: String): String {
        replies.generate(token, run, evidenceHash)
        return "redirect:/w/$token/maker/replies?run=$run"
    }

    @PostMapping("/w/{token}/maker/replies/approve")
    fun approve(
        @PathVariable token: String,
        @RequestParam run: UUID,
        @RequestParam group: String,
        @RequestParam evidenceHash: String,
        @RequestParam body: String,
        model: Model,
        response: HttpServletResponse,
    ): String {
        try {
            replies.approve(token, run, group, evidenceHash, body)
        } catch (_: UnsafeReplyException) {
            response.status = HttpServletResponse.SC_BAD_REQUEST
            model.addAttribute("replyError", "Keep the exact analysis reason. Only the listed greetings may be changed.")
            return maker(token, run, model)
        }
        return "redirect:/w/$token/maker/replies?run=$run"
    }

    @GetMapping("/w/{token}/wishes/{wishId}/reply")
    fun fan(
        @PathVariable token: String,
        @PathVariable wishId: UUID,
        @RequestParam run: UUID,
        model: Model,
        response: HttpServletResponse,
    ): String {
        response.setHeader("Cache-Control", "no-store")
        val reply = replies.approvedForWish(token, run, wishId)
        if (reply == null) {
            response.status = HttpServletResponse.SC_NO_CONTENT
            return "replies/empty"
        }
        model.addAttribute("reply", reply)
        return "replies/fan :: approved"
    }

    @ExceptionHandler(WorkspaceNotFoundException::class)
    fun notFound(response: HttpServletResponse): String {
        response.status = HttpServletResponse.SC_NOT_FOUND
        return "error/not-found"
    }

    @ExceptionHandler(InvalidReplyEvidenceException::class)
    fun stale(request: HttpServletRequest): Any {
        if (!wantsHtml(request)) {
            return ResponseEntity.status(409).body(mapOf("message" to "Reply evidence changed. Open the latest proposal."))
        }
        val variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<*, *>
        return ModelAndView("replies/stale", mapOf("token" to variables?.get("token")), org.springframework.http.HttpStatus.CONFLICT)
    }
}

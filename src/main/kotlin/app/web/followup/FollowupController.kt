package app.web.followup

import app.campaign.followup.FollowupDraftResult
import app.campaign.followup.FollowupService
import app.campaign.followup.InvalidFollowupException
import app.campaign.port.AnalysisFailure
import app.campaign.review.InvalidWishReviewException
import app.campaign.workspace.WorkspaceNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.util.UUID

@Controller
class FollowupController(private val followups: FollowupService) {
    @GetMapping("/w/{token}/maker/questions")
    fun maker(@PathVariable token: String, model: Model): String {
        model.addAttribute("token", token)
        model.addAttribute("page", followups.makerPage(token))
        return "followup/maker"
    }

    @PostMapping("/w/{token}/maker/questions")
    fun draft(@PathVariable token: String, redirect: RedirectAttributes): String {
        when (val result = followups.draft(token)) {
            is FollowupDraftResult.Drafted -> redirect.addFlashAttribute("questionsDrafted", true)
            is FollowupDraftResult.Failed -> redirect.addFlashAttribute("questionError", failureMessage(result.reason))
        }
        return "redirect:/w/$token/maker/questions"
    }

    @PostMapping("/w/{token}/maker/questions/{id}/approve")
    fun approve(
        @PathVariable token: String,
        @PathVariable id: UUID,
        @RequestParam text: String,
        @RequestParam(required = false, defaultValue = "") choices: List<String>,
        redirect: RedirectAttributes,
    ): String {
        followups.approve(token, id, text, choices)
        redirect.addFlashAttribute("questionApproved", true)
        return "redirect:/w/$token/maker/questions"
    }

    @GetMapping("/w/{token}/questions")
    fun fan(@PathVariable token: String, @RequestParam wish: UUID, model: Model): String {
        model.addAttribute("token", token)
        model.addAttribute("wishId", wish)
        model.addAttribute("questions", followups.fanQuestions(token, wish))
        model.addAttribute("answersByQuestion", followups.answers(token, wish).associateBy { it.questionId })
        return "followup/fan"
    }

    @PostMapping("/w/{token}/questions/{id}")
    fun answer(
        @PathVariable token: String,
        @PathVariable id: UUID,
        @RequestParam wish: UUID,
        @RequestParam choice: String,
        redirect: RedirectAttributes,
    ): String {
        followups.answer(token, id, wish, choice)
        redirect.addFlashAttribute("answerSaved", true)
        return "redirect:/w/$token/questions?wish=$wish"
    }

    @ExceptionHandler(WorkspaceNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(): String = "error/not-found"

    @ExceptionHandler(InvalidFollowupException::class, InvalidWishReviewException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalid(@PathVariable token: String, model: Model): String {
        model.addAttribute("token", token)
        model.addAttribute("followupError", "This question or answer could not be saved. Return to the question page and choose an approved option.")
        return "followup/invalid"
    }

    private fun failureMessage(reason: AnalysisFailure): String = when (reason) {
        AnalysisFailure.NOT_CONFIGURED -> "Question drafting is unavailable here. Existing questions can still be reviewed."
        AnalysisFailure.RUN_LIMIT_REACHED -> "The question drafting limit has been reached. Existing questions can still be reviewed."
        AnalysisFailure.TIMEOUT -> "Question drafting took too long. Existing questions are unchanged."
        else -> "Questions could not be drafted. Existing questions are unchanged."
    }
}

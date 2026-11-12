package app.campaign.replies

import java.util.UUID

data class ReplySource(val wishId: UUID, val quote: String)
data class ReplyEvidenceGroup(val groupId: String, val sources: List<ReplySource>, val reasons: List<String>)

sealed interface ReplyDraftResult {
    data class Success(val bodies: Map<String, String>, val model: String) : ReplyDraftResult
    data class Failure(val code: String) : ReplyDraftResult
}

fun interface ReplyDrafter {
    fun draft(groups: List<ReplyEvidenceGroup>): ReplyDraftResult
}

/** Free prose cannot establish semantic grounding; edits retain an exact validated reason. */
object ReplySafety {
    val greetings = listOf("Thank you for sharing your wish. ", "Thanks for your wish. ", "")
    private val numbers = Regex(
        "\\b(zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twel|thir|fif|" +
            "twent|thirt|fort|fift|sixt|sevent|eight|ninet|dozen|hundred|thousand|million|billion)\\w*\\b",
        RegexOption.IGNORE_CASE,
    )
    private val forbidden = Regex(
        "\\b(single|double|triple|pair|couple|first|second|third|half|quarter|percent|pric\\w*|cost\\w*|budget|" +
            "dollars?|euros?|pounds?|cents?|usd|eur|gbp|free|discount|shipping|delivery|refund|payment|charge|" +
            "guarantee\\w*|promise\\w*|will|shall|future|next|following|later|soon|plan\\w*|intend\\w*|expect\\w*|upcoming|" +
            "aim\\w*|schedule\\w*|launch\\w*|forthcoming|eventually|" +
            "buy|sell|sale|purchase|preorder|pre-order|paid|fee|deposit|we'll|i'll|you'll|they'll|" +
            "tomorrow|today|yesterday|" +
            "days?|weeks?|months?|years?|monday|tuesday|wednesday|thursday|friday|saturday|sunday|" +
            "january|february|march|april|may|june|july|august|september|october|november|december)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun isSafeReason(reason: String): Boolean = reason.isNotBlank() && reason.length <= 140 &&
        reason.all { it in ' '..'~' } && reason.none { it.isDigit() || it in "$%<>\\/=@" } &&
        !numbers.containsMatchIn(reason) && !forbidden.containsMatchIn(reason)

    fun body(reason: String): String = greetings.first() + reason

    fun validateBody(body: String, reasons: List<String>): Boolean = reasons.any { reason ->
        isSafeReason(reason) && greetings.any { body == it + reason }
    }
}

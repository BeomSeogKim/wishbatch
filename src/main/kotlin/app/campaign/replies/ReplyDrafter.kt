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

/** Bodies reuse only bounded explanations; free-form analysis prose is never copied into a reply. */
object ReplySafety {
    const val notServedExplanation = "This wish is classified as not served by the proposed batch."
    val greetings = listOf("Thank you for sharing your wish. ", "Thanks for your wish. ", "")
    private val explanations = setOf(
        notServedExplanation,
        "Requested format differs from this batch.",
        "This batch uses a different format.",
        "This batch has a different finish.",
        "The requested format differs.",
        "The stated requirement is outside this catalog.",
        "Paper goods excluded; hoodie or T-shirt required.",
        "Postcards are not the requested acrylic stands.",
        "Loose postcards are explicitly excluded.",
    )

    fun isSafeReason(reason: String): Boolean = reason in explanations

    fun body(reason: String): String {
        require(isSafeReason(reason)) { "Unsupported reply explanation" }
        return greetings.first() + reason
    }

    fun validateBody(body: String, reasons: List<String>): Boolean = reasons.any { reason ->
        isSafeReason(reason) && greetings.any { body == it + reason }
    }
}

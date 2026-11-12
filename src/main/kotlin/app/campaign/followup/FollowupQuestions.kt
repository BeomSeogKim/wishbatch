package app.campaign.followup

import app.campaign.model.CompatibilityVerdict
import app.campaign.model.NeedAttribute
import app.campaign.model.NeedStrength
import app.campaign.model.Option
import app.campaign.model.WishKind
import app.campaign.port.AnalysisOutput
import app.campaign.port.WishNeed
import java.util.UUID
import java.util.Locale

data class FollowupDraft(
    val text: String,
    val attribute: NeedAttribute,
    val variantKey: String,
    val choices: List<String>,
    val wishIds: List<UUID>,
)

data class ApprovedFollowupQuestion(
    val id: UUID,
    val runId: UUID,
    val optionId: UUID,
    val draft: FollowupDraft,
)

data class FollowupAnswer(val id: UUID, val questionId: UUID, val wishId: UUID, val choice: String)

data class FollowupResolution(
    val runId: UUID,
    val wishId: UUID,
    val optionId: UUID,
    val verdict: CompatibilityVerdict,
    val answers: List<FollowupAnswer>,
    val source: String = "FAN_ANSWER",
)

object FollowupQuestions {
    fun attributeForVariantKey(key: String): NeedAttribute? = when (key) {
        "format" -> NeedAttribute.FORMAT
        "size" -> NeedAttribute.SIZE
        "finish", "paper" -> NeedAttribute.FINISH
        "subject" -> NeedAttribute.SUBJECT
        "color" -> NeedAttribute.OTHER
        else -> null
    }

    fun matchesVariantNeed(option: Option, key: String, need: WishNeed): Boolean {
        if (need.strength == NeedStrength.PREFERENCE || need.attribute != attributeForVariantKey(key)) return false
        val qualifiers = when (key) {
            "finish", "paper" -> listOf("finish", "paper")
            "color" -> listOf("color", "colour")
            else -> listOf(key)
        }
        fun normalized(text: String) = text.trim().replace(Regex("(?U)\\s+"), " ").lowercase(Locale.ROOT)
        val value = normalized(need.value)
        return option.allowedVariants[key].orEmpty().any { choice ->
            val variant = normalized(choice)
            val names = listOf(variant) + qualifiers.flatMap { listOf("$variant $it", "$it $variant") }
            value in names && Regex("(?iuU)(?<![\\p{L}\\p{N}])${Regex.escape(choice)}(?![\\p{L}\\p{N}])")
                .containsMatchIn(need.quote)
        }
    }

    fun validate(drafts: List<FollowupDraft>, option: Option, output: AnalysisOutput) {
        require(drafts.size <= 3) { "At most three follow-up questions are allowed" }
        require(option.id in output.optionIds) { "Option is outside the analysis" }
        require(drafts.map { it.attribute }.distinct().size == drafts.size) { "Group each condition into one question" }
        for (draft in drafts) {
            require(draft.text.isNotBlank() && draft.text.length <= 240) { "A short question is required" }
            require(attributeForVariantKey(draft.variantKey) == draft.attribute) { "Variant key must identify a supported condition attribute" }
            val allowed = option.allowedVariants[draft.variantKey].orEmpty()
            require(draft.choices.size >= 2 && draft.choices.distinct().size == draft.choices.size &&
                draft.choices.all { it in allowed }) { "Choices must be exact allowed variants" }
            require(draft.wishIds.isNotEmpty() && draft.wishIds.distinct().size == draft.wishIds.size)
            for (wishId in draft.wishIds) {
                val wish = output.wishes.singleOrNull { it.wishId == wishId }
                require(wish?.kind == WishKind.REQUEST && wish.needs.any {
                    matchesVariantNeed(option, draft.variantKey, it)
                }) { "Questions must refer to a recorded condition" }
                require(output.verdicts.singleOrNull { it.wishId == wishId && it.optionId == option.id }
                    ?.verdict == CompatibilityVerdict.CONDITIONAL) { "Only Depends wishes can receive questions" }
            }
        }
    }

    /** Maker edits pass the same catalog and condition checks as the draft. */
    fun approve(runId: UUID, drafts: List<FollowupDraft>, option: Option, output: AnalysisOutput): List<ApprovedFollowupQuestion> {
        validate(drafts, option, output)
        return drafts.map {
            ApprovedFollowupQuestion(UUID.randomUUID(), runId, option.id,
                it.copy(choices = it.choices.toList(), wishIds = it.wishIds.toList()))
        }
    }

    /** A variant answer leaves unrelated or uncovered hard conditions unresolved. */
    fun resolve(
        runId: UUID,
        wishId: UUID,
        questions: List<ApprovedFollowupQuestion>,
        answers: List<FollowupAnswer>,
        option: Option,
        output: AnalysisOutput,
    ): FollowupResolution {
        require(questions.all { it.runId == runId && it.optionId == option.id }) { "Question snapshot does not match" }
        require(questions.map { it.id }.distinct().size == questions.size)
        validate(questions.filter { wishId in it.draft.wishIds }.map {
            it.draft.copy(wishIds = listOf(wishId))
        }, option, output)
        val wish = requireNotNull(output.wishes.singleOrNull { it.wishId == wishId && it.kind == WishKind.REQUEST })
        require(output.verdicts.singleOrNull { it.wishId == wishId && it.optionId == option.id }
            ?.verdict == CompatibilityVerdict.CONDITIONAL)
        val relevant = questions.filter { wishId in it.draft.wishIds }.associateBy { it.id }
        require(answers.map { it.id }.distinct().size == answers.size)
        require(answers.map { it.questionId }.distinct().size == answers.size) { "An answer cannot be counted twice" }
        for (answer in answers) {
            val question = requireNotNull(relevant[answer.questionId]) { "Answer is outside this wish" }
            require(answer.wishId == wishId && answer.choice in question.draft.choices) { "Answer must select an approved choice" }
        }
        val required = wish.needs.filter { it.strength != NeedStrength.PREFERENCE }
        val complete = required.isNotEmpty() && required.groupBy { it.attribute }.values.all { it.size == 1 } &&
            required.all { need -> answers.any { answer ->
                matchesVariantNeed(option, relevant.getValue(answer.questionId).draft.variantKey, need)
            } }
        return FollowupResolution(runId, wishId, option.id,
            if (complete) CompatibilityVerdict.COMPATIBLE else CompatibilityVerdict.CONDITIONAL, answers.toList())
    }
}

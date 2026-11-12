package app.campaign.followup

import app.analysis.followup.FollowupDraftValidator
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.NeedAttribute
import app.campaign.model.NeedStrength
import app.campaign.model.Option
import app.campaign.model.WishKind
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalyzedWish
import app.campaign.port.WishNeed
import app.campaign.port.WishVerdict
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FollowupQuestionsTests {
    private val run = UUID.randomUUID()
    private val wish = UUID.randomUUID()
    private val option = Option(UUID.randomUUID(), "Art print", "print", emptyMap(),
        mapOf("finish" to listOf("matte", "glossy"), "size" to listOf("small", "large")),
        500, "USD", 3, 10, "Maker-confirmed delivery", "US")
    private val finish = WishNeed(NeedStrength.HARD, NeedAttribute.FINISH, "matte", "Only matte")
    private val output = AnalysisOutput(
        listOf(AnalyzedWish(wish, WishKind.REQUEST, listOf(finish))),
        listOf(WishVerdict(wish, option.id, CompatibilityVerdict.CONDITIONAL, "Confirm the finish.")),
        null, "Review conditions.", emptyList(), emptyList(), emptyList(), listOf(option.id),
    )
    private val draft = FollowupDraft("Which finish would meet your condition?", NeedAttribute.FINISH,
        "finish", listOf("matte", "glossy"), listOf(wish))

    @Test
    fun `variant quote matching respects Unicode word boundaries and whitespace`() {
        for (quote in listOf("matters", "formatte", "matte\u8272", "matte2")) {
            assertEquals(false, FollowupQuestions.matchesVariantNeed(option, "finish", finish.copy(quote = quote)))
        }
        assertEquals(true, FollowupQuestions.matchesVariantNeed(option, "finish",
            finish.copy(value = "matte\u00a0finish", quote = "Only MATTE finish")))
    }

    @Test
    fun `validated model draft can be edited and approved against the same catalog`() {
        assertEquals(listOf(draft), FollowupDraftValidator.validate(listOf(draft), option, output))
        val edited = draft.copy(text = "Which finish works for you?")
        val approved = FollowupQuestions.approve(run, listOf(edited), option, output).single()
        assertEquals(edited, approved.draft)
        assertEquals(run, approved.runId)
        assertEquals(option.id, approved.optionId)
        assertFailsWith<IllegalArgumentException> {
            FollowupQuestions.approve(run, listOf(edited.copy(choices = listOf("matte", "metallic"))), option, output)
        }
    }

    @Test
    fun `answer resolves deterministically with exact provenance and leaves analysis unchanged`() {
        val before = output.copy()
        val questions = FollowupQuestions.approve(run, listOf(draft), option, output)
        val answer = FollowupAnswer(UUID.randomUUID(), questions.single().id, wish, "glossy")
        val result = FollowupQuestions.resolve(run, wish, questions, listOf(answer), option, output)
        assertEquals(CompatibilityVerdict.COMPATIBLE, result.verdict)
        assertEquals(run, result.runId)
        assertEquals(wish, result.wishId)
        assertEquals(option.id, result.optionId)
        assertEquals("FAN_ANSWER", result.source)
        assertEquals(listOf(answer), result.answers)
        assertEquals(before, output)
        assertEquals(result, FollowupQuestions.resolve(run, wish, questions, listOf(answer), option, output))
    }

    @Test
    fun `every hard or unknown condition needs an answer before compatible resolution`() {
        val sizeNeed = WishNeed(NeedStrength.UNKNOWN, NeedAttribute.SIZE, "small", "small")
        val expanded = output.copy(wishes = listOf(output.wishes.single().copy(needs = listOf(finish, sizeNeed))))
        val sizeDraft = FollowupDraft("Which size works for you?", NeedAttribute.SIZE, "size", listOf("small", "large"), listOf(wish))
        val questions = FollowupQuestions.approve(run, listOf(draft, sizeDraft), option, expanded)
        val answers = questions.map { FollowupAnswer(UUID.randomUUID(), it.id, wish, it.draft.choices.first()) }
        for (partial in listOf(emptyList(), answers.take(1), answers.takeLast(1))) {
            assertEquals(CompatibilityVerdict.CONDITIONAL,
                FollowupQuestions.resolve(run, wish, questions, partial, option, expanded).verdict)
        }
        assertEquals(CompatibilityVerdict.COMPATIBLE,
            FollowupQuestions.resolve(run, wish, questions, answers, option, expanded).verdict)
    }

    @Test
    fun `commercial unknowns stay conditional after variant answers and preferences do not block`() {
        val questions = FollowupQuestions.approve(run, listOf(draft), option, output)
        val answers = listOf(FollowupAnswer(UUID.randomUUID(), questions.single().id, wish, "matte"))
        for (attribute in listOf(NeedAttribute.PRICE, NeedAttribute.QUANTITY, NeedAttribute.TIMING, NeedAttribute.REGION, NeedAttribute.OTHER)) {
            val need = WishNeed(NeedStrength.UNKNOWN, attribute, "Unresolved", "Unresolved")
            val expanded = output.copy(wishes = listOf(output.wishes.single().copy(needs = listOf(finish, need))))
            assertEquals(CompatibilityVerdict.CONDITIONAL,
                FollowupQuestions.resolve(run, wish, questions, answers, option, expanded).verdict)
            val preferred = expanded.copy(wishes = listOf(expanded.wishes.single().copy(needs = listOf(finish, need.copy(strength = NeedStrength.PREFERENCE)))))
            assertEquals(CompatibilityVerdict.COMPATIBLE,
                FollowupQuestions.resolve(run, wish, questions, answers, option, preferred).verdict)
        }
    }

    @Test
    fun `multiple independent conditions on the same attribute stay unresolved`() {
        val expanded = output.copy(wishes = listOf(output.wishes.single().copy(needs = listOf(
            finish, WishNeed(NeedStrength.HARD, NeedAttribute.FINISH, "waterproof", "must be waterproof"),
        ))))
        val questions = FollowupQuestions.approve(run, listOf(draft), option, expanded)
        val answers = listOf(FollowupAnswer(UUID.randomUUID(), questions.single().id, wish, "matte"))
        assertEquals(CompatibilityVerdict.CONDITIONAL,
            FollowupQuestions.resolve(run, wish, questions, answers, option, expanded).verdict)
    }

    @Test
    fun `sole waterproof finish condition cannot be resolved by a matte or glossy answer`() {
        val waterproof = output.copy(wishes = listOf(output.wishes.single().copy(needs = listOf(
            WishNeed(NeedStrength.HARD, NeedAttribute.FINISH, "waterproof", "must be waterproof"),
        ))))
        assertFailsWith<IllegalArgumentException> { FollowupQuestions.approve(run, listOf(draft), option, waterproof) }
        assertEquals(CompatibilityVerdict.CONDITIONAL,
            FollowupQuestions.resolve(run, wish, emptyList(), emptyList(), option, waterproof).verdict)
        for ((value, quote) in listOf("matte" to "must be waterproof", "matte and waterproof" to "must be matte and waterproof")) {
            val unsupported = waterproof.copy(wishes = listOf(waterproof.wishes.single().copy(needs = listOf(
                WishNeed(NeedStrength.HARD, NeedAttribute.FINISH, value, quote),
            ))))
            assertFailsWith<IllegalArgumentException> { FollowupQuestions.validate(listOf(draft), option, unsupported) }
        }
    }

    @Test
    fun `variant values from an unrelated attribute cannot resolve a condition`() {
        val invalid = draft.copy(variantKey = "size", choices = listOf("small", "large"))
        assertFailsWith<IllegalArgumentException> { FollowupQuestions.validate(listOf(invalid), option, output) }
    }

    @Test
    fun `catalog paper and explicit color conditions use their documented need attributes`() {
        val paper = Option(option.workspaceId, "Paper print", "print", emptyMap(),
            mapOf("paper" to listOf("matte", "satin")), 500, "USD", 3, 10, "Maker terms", "US")
        val paperOutput = output.copy(optionIds = listOf(paper.id), verdicts = listOf(output.verdicts.single().copy(optionId = paper.id)))
        val paperDraft = draft.copy(variantKey = "paper", choices = listOf("matte", "satin"))
        FollowupQuestions.validate(listOf(paperDraft), paper, paperOutput)
        for (value in listOf("matte", "matte finish", "finish matte", "matte paper", "paper matte")) {
            val aliased = paperOutput.copy(wishes = listOf(paperOutput.wishes.single().copy(needs = listOf(
                finish.copy(value = value, quote = "Only matte paper"),
            ))))
            FollowupQuestions.validate(listOf(paperDraft), paper, aliased)
        }

        val color = Option(option.workspaceId, "Cotton pouch", "pouch", emptyMap(),
            mapOf("color" to listOf("natural", "blue")), 500, "USD", 3, 10, "Maker terms", "US")
        val colorNeed = WishNeed(NeedStrength.HARD, NeedAttribute.OTHER, "blue", "Only blue")
        val colorOutput = output.copy(optionIds = listOf(color.id),
            verdicts = listOf(output.verdicts.single().copy(optionId = color.id)),
            wishes = listOf(output.wishes.single().copy(needs = listOf(colorNeed))))
        val colorDraft = draft.copy(attribute = NeedAttribute.OTHER, variantKey = "color", choices = listOf("natural", "blue"))
        val approved = FollowupQuestions.approve(run, listOf(colorDraft), color, colorOutput)
        val answer = FollowupAnswer(UUID.randomUUID(), approved.single().id, wish, "blue")
        assertEquals(CompatibilityVerdict.COMPATIBLE,
            FollowupQuestions.resolve(run, wish, approved, listOf(answer), color, colorOutput).verdict)
        val unrelated = colorOutput.copy(wishes = listOf(colorOutput.wishes.single().copy(needs = listOf(
            WishNeed(NeedStrength.UNKNOWN, NeedAttribute.OTHER, "Unresolved delivery", "When will it arrive?"),
        ))))
        assertFailsWith<IllegalArgumentException> { FollowupQuestions.validate(listOf(colorDraft), color, unrelated) }
    }

    @Test
    fun `approval and validation snapshot mutable model input`() {
        val choices = mutableListOf("matte", "glossy")
        val wishes = mutableListOf(wish)
        val drafts = mutableListOf(draft.copy(choices = choices, wishIds = wishes))
        val validated = FollowupDraftValidator.validate(drafts, option, output)
        val approved = FollowupQuestions.approve(run, drafts, option, output)
        choices.clear()
        wishes.clear()
        drafts.clear()
        assertEquals(listOf(draft), validated)
        assertEquals(draft, approved.single().draft)
    }

    @Test
    fun `invented duplicate or missing choices and blank or long questions are rejected`() {
        for (invalid in listOf(
            draft.copy(choices = listOf("matte", "invented")),
            draft.copy(choices = listOf("Matte", "glossy")),
            draft.copy(choices = listOf("matte", "matte")),
            draft.copy(choices = listOf("matte")),
            draft.copy(choices = emptyList()),
            draft.copy(text = " "),
            draft.copy(text = "x".repeat(241)),
            draft.copy(variantKey = "price"),
            draft.copy(attribute = NeedAttribute.PRICE),
            draft.copy(wishIds = emptyList()),
            draft.copy(wishIds = listOf(wish, wish)),
            draft.copy(wishIds = listOf(UUID.randomUUID())),
        )) assertFailsWith<IllegalArgumentException> { FollowupQuestions.validate(listOf(invalid), option, output) }
        assertFailsWith<IllegalArgumentException> { FollowupQuestions.validate(List(4) { draft }, option, output) }
        assertFailsWith<IllegalArgumentException> { FollowupQuestions.validate(listOf(draft, draft), option, output) }
    }

    @Test
    fun `model questions cannot introduce numeric commercial terms or audience counts`() {
        for (text in listOf("Would you pay 20 dollars?", "Will twenty fans choose matte?", "Ready by 2027?", "A dozen orders?", "Would ２０ work?")) {
            assertFailsWith<IllegalArgumentException> {
                FollowupDraftValidator.validate(listOf(draft.copy(text = text)), option, output)
            }
        }
    }

    @Test
    fun `questions cannot target nonrequests preferences other options or resolved wishes`() {
        val invalidOutputs = listOf(
            output.copy(optionIds = listOf(UUID.randomUUID())),
            output.copy(wishes = listOf(output.wishes.single().copy(kind = WishKind.NOT_A_REQUEST))),
            output.copy(wishes = listOf(output.wishes.single().copy(needs = listOf(finish.copy(strength = NeedStrength.PREFERENCE))))),
            output.copy(verdicts = listOf(output.verdicts.single().copy(verdict = CompatibilityVerdict.COMPATIBLE))),
            output.copy(verdicts = listOf(output.verdicts.single().copy(verdict = CompatibilityVerdict.INCOMPATIBLE))),
            output.copy(verdicts = listOf(output.verdicts.single().copy(optionId = UUID.randomUUID()))),
        )
        for (invalid in invalidOutputs) assertFailsWith<IllegalArgumentException> {
            FollowupQuestions.validate(listOf(draft), option, invalid)
        }
    }

    @Test
    fun `cross wish run option and question answers and repeated submissions are rejected`() {
        val questions = FollowupQuestions.approve(run, listOf(draft), option, output)
        val question = questions.single()
        val answer = FollowupAnswer(UUID.randomUUID(), question.id, wish, "matte")
        for (invalid in listOf(
            answer.copy(choice = "metallic"), answer.copy(wishId = UUID.randomUUID()), answer.copy(questionId = UUID.randomUUID()),
        )) assertFailsWith<IllegalArgumentException> {
            FollowupQuestions.resolve(run, wish, questions, listOf(invalid), option, output)
        }
        assertFailsWith<IllegalArgumentException> {
            FollowupQuestions.resolve(run, wish, questions, listOf(answer, answer.copy(id = UUID.randomUUID())), option, output)
        }
        for (invalid in listOf(question.copy(runId = UUID.randomUUID()), question.copy(optionId = UUID.randomUUID()))) {
            assertFailsWith<IllegalArgumentException> {
                FollowupQuestions.resolve(run, wish, listOf(invalid), listOf(answer), option, output)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            FollowupQuestions.resolve(run, UUID.randomUUID(), questions, listOf(answer), option, output)
        }
    }
}

package app.campaign.workbench

import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Money
import app.campaign.model.NeedAttribute
import app.campaign.model.NeedStrength
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.model.WishSource
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalysisValidationFlag
import app.campaign.port.AnalysisValidationRule
import app.campaign.port.AnalyzedWish
import app.campaign.port.Proposal
import app.campaign.port.ProposalEvidence
import app.campaign.port.WishNeed
import app.campaign.port.WishVerdict
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WishWorkbenchTests {
    private val workspaceId = UUID.randomUUID()
    private val option = Option(workspaceId, "Print", "print", emptyMap(), emptyMap(), 650, "USD", 3, 10,
        "Two weeks", "US")
    private val other = Option(workspaceId, "Cards", "cards", emptyMap(), emptyMap(), 500, "USD", 3, 10,
        "Two weeks", "US")

    @Test
    fun `source rows remain distinct and only literal normalized quotes are exposed`() {
        val wish = wish("I want a matte\u00a0print")
        val missing = wish("A wish added after analysis")
        val output = output(wish, needs = listOf(
            WishNeed(NeedStrength.HARD, NeedAttribute.FINISH, "matte", "matte print"),
            WishNeed(NeedStrength.PREFERENCE, NeedAttribute.SIZE, "large", "invented quote"),
        ))
        val rows = wishWorkbenchRows(listOf(wish, wish, missing), listOf(option, option), output, emptyList())
        assertEquals(2, rows.size)
        assertEquals(listOf("matte print", "I want"), rows.first().evidence)
        assertEquals(1, rows.first().labels.size)
        assertEquals("Unknown", rows.first().language)
        assertEquals("IMPORTED", rows.first().source)
        assertEquals("UNANALYZED", rows.last().labels.single().label)
        assertTrue(rows.last().evidence.isEmpty())
    }

    @Test
    fun `validation index paths and explicit ids attach only to their own wish`() {
        val first = wish("A matte print")
        val second = wish("A card")
        val output = output(first).copy(wishes = listOf(
            AnalyzedWish(first.id, WishKind.REQUEST, emptyList()),
            AnalyzedWish(second.id, WishKind.REQUEST, emptyList()),
        ))
        val flags = listOf(
            AnalysisValidationFlag(AnalysisValidationRule.V2, "wishes[0].needs[0]", "Dropped quote"),
            AnalysisValidationFlag(AnalysisValidationRule.V1, "verdicts[0].reason", "Dropped reason"),
            AnalysisValidationFlag(AnalysisValidationRule.UNANALYZED_WISH, "wishes[${second.id}]", "Missing reading"),
            AnalysisValidationFlag(AnalysisValidationRule.V6, "notServed", "Rebuilt groups"),
            AnalysisValidationFlag(AnalysisValidationRule.V2, "wishes[10].needs", "Another index"),
        )
        val rows = wishWorkbenchRows(listOf(first, second), listOf(option), output, flags)
        assertEquals(listOf("V2: Dropped quote", "V1: Dropped reason"), rows[0].flags)
        assertEquals(listOf("UNANALYZED_WISH: Missing reading"), rows[1].flags)
    }

    @Test
    fun `paired filters and counts use unique wish labels and ignore extracted amounts and quantities`() {
        val wish = wish("Matte please")
        val output = output(wish).copy(
            wishes = listOf(AnalyzedWish(wish.id, WishKind.REQUEST, emptyList(), Money(9900, "USD"), 999)),
            verdicts = listOf(
                WishVerdict(wish.id, option.id, CompatibilityVerdict.COMPATIBLE, "Matte is available"),
                WishVerdict(wish.id, other.id, CompatibilityVerdict.INCOMPATIBLE, "Different format"),
            ),
            optionIds = listOf(option.id, other.id),
        )
        val row = wishWorkbenchRows(listOf(wish), listOf(option, other), output, emptyList()).single()
        assertTrue(WorkbenchFilter(option.id, "COMPATIBLE", "MATTE").matches(row))
        assertFalse(WorkbenchFilter(other.id, "COMPATIBLE").matches(row))
        assertTrue(WorkbenchFilter(other.id, "INCOMPATIBLE", "different format").matches(row))
        assertEquals(mapOf("COMPATIBLE" to 1), workbenchCounts(listOf(row, row), option.id))
        assertEquals(mapOf("INCOMPATIBLE" to 1), workbenchCounts(listOf(row, row), other.id))
    }

    @Test
    fun `non requests and hidden source wishes remain visible as explicit states`() {
        val wish = wish("Love your work").apply { hidden = true }
        val output = output(wish).copy(wishes = listOf(AnalyzedWish(wish.id, WishKind.NOT_A_REQUEST, emptyList())))
        val row = wishWorkbenchRows(listOf(wish), listOf(option, other), output, emptyList()).single()
        assertTrue(row.hidden)
        assertTrue(row.labels.all { it.label == "NOT_A_REQUEST" })
        assertEquals(mapOf("NOT_A_REQUEST" to 1), workbenchCounts(listOf(row), null))
    }

    private fun wish(text: String) = Wish(workspaceId, text, WishSource.IMPORTED, createdAt = Instant.EPOCH)

    private fun output(wish: Wish, needs: List<WishNeed> = emptyList()) = AnalysisOutput(
        wishes = listOf(AnalyzedWish(wish.id, WishKind.REQUEST, needs)),
        verdicts = listOf(WishVerdict(wish.id, option.id, CompatibilityVerdict.COMPATIBLE, "Matches")),
        proposal = Proposal(option.id, emptyMap(), listOf(ProposalEvidence("A format request", wish.id, "I want"))),
        noProposalReason = null,
        notServed = emptyList(),
        questionsForMaker = emptyList(),
        alternatives = emptyList(),
        optionIds = listOf(option.id),
    )
}

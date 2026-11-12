package app.campaign

import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.model.WishSource
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalyzedWish
import app.campaign.port.WishVerdict
import app.campaign.rules.OptionWishCounts
import app.campaign.rules.analysisInputHash
import app.campaign.rules.optionWishCounts
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class AnalysisRulesTests {
    private val workspaceId = UUID(0, 1)

    private fun option(
        id: Long,
        attributes: Map<String, String> = linkedMapOf("material" to "cotton", "fit" to "loose"),
        variants: Map<String, List<String>> = linkedMapOf("color" to listOf("blue", "red"), "size" to listOf("M", "L")),
        name: String = "Shirt",
        format: String = "shirt",
    ) = Option(workspaceId, name, format, attributes, variants, 1000, "USD", 10, 50, "Two weeks", "US", UUID(0, id))

    private fun wish(id: Long, text: String = "A blue shirt") =
        Wish(workspaceId, text, WishSource.ONSITE, createdAt = Instant.EPOCH, id = UUID(0, id))

    @Test
    fun `hash is stable under snapshot map and allowed value reordering`() {
        val first = option(2)
        val reordered = option(
            2,
            first.fixedAttributes.entries.reversed().associate { it.toPair() },
            first.allowedVariants.entries.reversed().associate { it.key to it.value.reversed() },
        )
        val hash = analysisInputHash(listOf(first, option(3)), listOf(wish(4), wish(5)))
        assertEquals(hash, analysisInputHash(listOf(option(3), reordered), listOf(wish(5), wish(4))))
        assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `hash changes with exact wish text and product constraints`() {
        val hash = analysisInputHash(listOf(option(2)), listOf(wish(4)))
        assertNotEquals(hash, analysisInputHash(listOf(option(2)), listOf(wish(4, "A red shirt"))))
        assertNotEquals(hash, analysisInputHash(listOf(option(2)), listOf(wish(4, "A blue shirt "))))
        assertNotEquals(hash, analysisInputHash(listOf(option(2, variants = mapOf("color" to listOf("green")))), listOf(wish(4))))
    }

    @Test
    fun `canonical fields cannot collide through concatenation`() {
        assertNotEquals(
            analysisInputHash(listOf(option(2, name = "ab", format = "c")), emptyList()),
            analysisInputHash(listOf(option(2, name = "a", format = "bc")), emptyList()),
        )
    }

    @Test
    fun `wish metadata does not change analysis input`() {
        val original = wish(4)
        val metadataChanged = Wish(UUID(0, 99), original.text, WishSource.IMPORTED, "Another fan", Instant.MAX, true, original.id)
        assertEquals(analysisInputHash(emptyList(), listOf(original)), analysisInputHash(emptyList(), listOf(metadataChanged)))
    }

    @Test
    fun `counts use verdict categories and non requests rather than quantities`() {
        val options = listOf(UUID(0, 2), UUID(0, 3))
        val wishes = (4L..7L).map { id ->
            AnalyzedWish(UUID(0, id), if (id == 7L) WishKind.NOT_A_REQUEST else WishKind.REQUEST, emptyList(), quantity = 100)
        }
        val verdicts = listOf(
            WishVerdict(wishes[0].wishId, options[0], CompatibilityVerdict.COMPATIBLE, "Matches"),
            WishVerdict(wishes[1].wishId, options[0], CompatibilityVerdict.CONDITIONAL, "Needs confirmation"),
            WishVerdict(wishes[2].wishId, options[0], CompatibilityVerdict.INCOMPATIBLE, "Wrong format"),
        ) + wishes.take(3).map { WishVerdict(it.wishId, options[1], CompatibilityVerdict.INCOMPATIBLE, "Wrong format") }
        val output = AnalysisOutput(wishes, verdicts, null, "No proposal", emptyList(), emptyList(), emptyList(), options)
        assertEquals(listOf(OptionWishCounts(options[0], 1, 1, 1, 1), OptionWishCounts(options[1], 0, 0, 3, 1)), optionWishCounts(output))
    }

    @Test
    fun `every option is counted with no requests or no wishes`() {
        val options = listOf(UUID(0, 2), UUID(0, 3))
        val output = AnalysisOutput(emptyList(), emptyList(), null, "No requests", emptyList(), emptyList(), emptyList(), options)
        assertEquals(options.map { OptionWishCounts(it, 0, 0, 0, 0) }, optionWishCounts(output))
        val nonRequest = AnalyzedWish(UUID(0, 4), WishKind.NOT_A_REQUEST, emptyList())
        assertEquals(options.map { OptionWishCounts(it, 0, 0, 0, 1) }, optionWishCounts(output.copy(wishes = listOf(nonRequest))))
        assertEquals(emptyList(), optionWishCounts(output.copy(optionIds = emptyList())))
    }
}

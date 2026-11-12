package app.campaign.demand

import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Money
import app.campaign.model.NeedAttribute
import app.campaign.model.NeedStrength
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.model.WishSource
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalyzedWish
import app.campaign.port.Proposal
import app.campaign.port.WishNeed
import app.campaign.port.WishVerdict
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DemandExplorerTests {
    private val workspaceId = UUID.randomUUID()
    private val first = option("Cards")
    private val second = option("Prints")

    @Test
    fun `strict caps use original USD quotes and preserve inclusive and exclusive boundaries`() {
        for ((quote, expected) in listOf(
            "At most USD 10.00" to 1000L,
            "up to US$ 10.01 each" to 1001L,
            "if the whole set costs less than $15" to 1499L,
            "only at $10 or less per set" to 1000L,
            "under $0.01" to 0L,
            "below $10" to 999L,
            "no more than $92233720368547758.07" to Long.MAX_VALUE,
        )) {
            val wish = analyzed(UUID.randomUUID(), listOf(quote), normalized = Money(1, "USD"))
            val parsed = quotedPriceLimit(wish, "Cards please. $quote", "USD")
            assertEquals(PriceEvidenceStatus.QUOTED_LIMIT, parsed.status, quote)
            assertEquals(expected, parsed.maximumMinor, quote)
            assertEquals(listOf(quote), parsed.quotes)
        }
    }

    @Test
    fun `ambiguous totals currencies quantities unsupported grammar and overflow never become unit caps`() {
        for (quote in listOf(
            "$12 including delivery is the most I can spend.",
            "provided the total for both is no more than $24",
            "up to EUR 10", "up to CAD $10", "up to CA$10", "up to $10-20",
            "up to $1,000", "up to $10.001", "up to $-10", "up to $0",
            "up to $92233720368547758.08", "up to $10 for two", "roughly $10",
            "I used to pay up to $10", "up to $10 if it ships tomorrow",
        )) {
            val parsed = quotedPriceLimit(analyzed(UUID.randomUUID(), listOf(quote)), quote, "USD")
            assertEquals(PriceEvidenceStatus.AMBIGUOUS, parsed.status, quote)
            assertNull(parsed.maximumMinor, quote)
        }
        val wish = analyzed(UUID.randomUUID(), listOf("up to $10"))
        assertEquals(PriceEvidenceStatus.AMBIGUOUS, quotedPriceLimit(wish, "up to $10", "CAD").status)
        assertEquals(PriceEvidenceStatus.AMBIGUOUS, quotedPriceLimit(wish, "Cards please", "USD").status)
        assertEquals(emptyList(), quotedPriceLimit(wish, "Cards please", "USD").quotes)
    }

    @Test
    fun `missing preferred and unknown limits stay distinct from explicitly unlimited wishes`() {
        val wish = analyzed(UUID.randomUUID(), emptyList(), normalized = Money(100, "USD"))
        assertEquals(PriceEvidenceStatus.MISSING, quotedPriceLimit(wish, "Cards please", "USD").status)
        val unlimited = analyzed(UUID.randomUUID(), listOf("No budget limit"))
        assertEquals(PriceEvidenceStatus.EXPLICITLY_UNBOUNDED, quotedPriceLimit(unlimited, "No budget limit", "USD").status)
        val preferred = analyzed(UUID.randomUUID(), listOf("up to $10")).let {
            it.copy(needs = it.needs.map { need -> need.copy(strength = NeedStrength.PREFERENCE) })
        }
        assertEquals(PriceEvidenceStatus.AMBIGUOUS, quotedPriceLimit(preferred, "up to $10", "USD").status)
        val twoCaps = analyzed(UUID.randomUUID(), listOf("up to $10", "at most USD 8.00"))
        assertEquals(800L, quotedPriceLimit(twoCaps, "up to $10; at most USD 8.00", "USD").maximumMinor)
    }

    @Test
    fun `clipped quotes cannot drop aggregate currency shipping or historical context`() {
        for (source in listOf("up to $10 including shipping", "CAD up to $10", "I used to pay up to $10",
            "up to $10 for two", "up to $10 if it ships tomorrow", "I will not pay up to $10")) {
            val parsed = quotedPriceLimit(analyzed(UUID.randomUUID(), listOf("up to $10")), source, "USD")
            assertEquals(PriceEvidenceStatus.AMBIGUOUS, parsed.status, source)
            assertNull(parsed.maximumMinor)
        }
        val unlimited = analyzed(UUID.randomUUID(), listOf("No budget limit"))
        assertEquals(PriceEvidenceStatus.AMBIGUOUS, quotedPriceLimit(unlimited, "No budget limit for shipping", "USD").status)
        val nonliteral = analyzed(UUID.randomUUID(), listOf("up to USD 10"))
        assertEquals(PriceEvidenceStatus.AMBIGUOUS, quotedPriceLimit(nonliteral, "up to USD\n10", "USD").status)
        assertEquals(emptyList(), quotedPriceLimit(nonliteral, "up to USD\n10", "USD").quotes)
    }

    @Test
    fun `curve counts distinct visible compatible requests and excludes unknown budgets`() {
        val sources = listOf("up to $10", "under $10", "No budget limit", "Cards please", "up to EUR 10",
            "up to $20", "up to $30", "up to $40", "up to $50").map(::wish)
        sources[0].duplicateCount = 99
        sources[7].hidden = true
        val requests = sources.mapIndexed { index, source ->
            analyzed(source.id, if (index == 3) emptyList() else listOf(source.text))
                .copy(kind = if (index == 6) WishKind.NOT_A_REQUEST else WishKind.REQUEST, quantity = 100)
        }
        val verdicts = sources.mapIndexed { index, source ->
            WishVerdict(source.id, first.id, when (index) {
                5 -> CompatibilityVerdict.CONDITIONAL
                8 -> CompatibilityVerdict.INCOMPATIBLE
                else -> CompatibilityVerdict.COMPATIBLE
            }, "Catalog assessment.")
        }
        val output = output(requests, verdicts + verdicts.first())
        val explorer = assertNotNull(exploreDemand(output, listOf(first, second), sources, first.id, 1500, 3))
        assertEquals(5, explorer.compatible)
        assertEquals(1, explorer.missing)
        assertEquals(1, explorer.ambiguous)
        assertEquals(1, explorer.unbounded)
        assertEquals(listOf(1L, 999L, 1000L, 1001L, 1500L), explorer.points.map { it.priceMinor })
        assertEquals(listOf(3, 3, 2, 1, 1), explorer.points.map { it.compatible })
        assertEquals(listOf(sources[2].id), explorer.points.last().wishIds)
        assertEquals(setOf(3), explorer.points.map { it.minimum }.toSet())
    }

    @Test
    fun `alternative coverage is pairwise set difference rather than subtraction of counts`() {
        val sources = listOf("up to $10", "Prints please", "Shared format please").map(::wish)
        val requests = sources.map { analyzed(it.id, emptyList()) }
        val verdicts = listOf(
            WishVerdict(sources[0].id, first.id, CompatibilityVerdict.COMPATIBLE, "Fits"),
            WishVerdict(sources[2].id, first.id, CompatibilityVerdict.COMPATIBLE, "Fits"),
            WishVerdict(sources[1].id, second.id, CompatibilityVerdict.COMPATIBLE, "Fits"),
            WishVerdict(sources[2].id, second.id, CompatibilityVerdict.COMPATIBLE, "Fits"),
        )
        val explorer = assertNotNull(exploreDemand(output(requests, verdicts), listOf(first, second), sources, first.id))
        assertEquals(listOf(2, 2), explorer.alternatives.map { it.compatible })
        assertEquals(listOf(sources[0].id), explorer.alternatives[0].differences.single().wishes.map { it.wishId })
        assertEquals(listOf(sources[1].id), explorer.alternatives[1].differences.single().wishes.map { it.wishId })
        assertEquals(explorer, exploreDemand(output(requests.reversed(), verdicts.reversed()), listOf(second, first), sources.reversed(), first.id))
    }

    @Test
    fun `empty and missing sources foreign options and extreme prices stay bounded`() {
        val source = wish("up to $92233720368547758.07")
        val output = output(listOf(analyzed(source.id, listOf(source.text))), listOf(
            WishVerdict(source.id, first.id, CompatibilityVerdict.COMPATIBLE, "Fits"),
        ))
        val explorer = assertNotNull(exploreDemand(output, listOf(first), listOf(source), first.id, Long.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(listOf(1L, Long.MAX_VALUE), explorer.points.map { it.priceMinor })
        assertEquals(first.minBatch, explorer.minimum)
        val empty = assertNotNull(exploreDemand(output, listOf(first), emptyList(), first.id))
        assertEquals(0, empty.compatible)
        assertEquals(listOf(1L, first.unitCost), empty.points.map { it.priceMinor })
        assertEquals(setOf(0), empty.points.map { it.compatible }.toSet())
        assertNull(exploreDemand(output, listOf(first), listOf(source), UUID.randomUUID()))
        val foreign = option("Foreign", UUID.randomUUID())
        assertEquals(1, assertNotNull(exploreDemand(output, listOf(first, foreign), listOf(source), first.id)).alternatives.size)
    }

    private fun analyzed(id: UUID, quotes: List<String>, normalized: Money? = Money(999999, "USD")) =
        AnalyzedWish(id, WishKind.REQUEST, quotes.map { WishNeed(NeedStrength.HARD, NeedAttribute.PRICE, "Ignored", it) }, normalized)

    private fun wish(text: String) = Wish(workspaceId, text, WishSource.ONSITE, createdAt = Instant.EPOCH)
    private fun option(name: String, workspace: UUID = workspaceId) = Option(workspace, name, "cards", emptyMap(), emptyMap(),
        100, "USD", 2, 10, "Two weeks", "US")
    private fun output(wishes: List<AnalyzedWish>, verdicts: List<WishVerdict>) = AnalysisOutput(wishes, verdicts,
        Proposal(first.id, emptyMap(), emptyList()), null, emptyList(), emptyList(), emptyList(), listOf(first.id, second.id))
}

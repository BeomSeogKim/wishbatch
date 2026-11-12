package app.campaign.nextbatch

import app.campaign.model.*
import app.campaign.port.*
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Test
import kotlin.test.*

class NextBatchTests {
    private val workspace = UUID.randomUUID()
    private fun option(name: String) = Option(workspace, name, "format", emptyMap(), emptyMap(), 1, "USD", 1, 10, "soon", "US")
    private fun wish(text: String, hidden: Boolean = false) = Wish(workspace, text, WishSource.IMPORTED, createdAt = Instant.EPOCH, hidden = hidden)
    private fun need(quote: String) = WishNeed(NeedStrength.HARD, NeedAttribute.FORMAT, "format", quote)

    @Test
    fun `rank unique request intersection and retain only grounded visible quotes`() {
        val chosen = option("Chosen")
        val a = option("A")
        val b = option("B")
        val request = wish("A small print please")
        val hidden = wish("hidden request", true)
        val conditional = wish("conditional request")
        val nonRequest = wish("lovely work")
        val ungrounded = wish("another request")
        val wishes = listOf(request, hidden, conditional, nonRequest, ungrounded)
        val analyzed = wishes.map { AnalyzedWish(it.id, if (it == nonRequest) WishKind.NOT_A_REQUEST else WishKind.REQUEST,
            listOf(need(if (it == request) "small print" else "invented"))) }
        fun verdict(w: Wish, o: Option, v: CompatibilityVerdict) = WishVerdict(w.id, o.id, v, "reason")
        val verdicts = wishes.flatMap { w -> listOf(
            verdict(w, chosen, if (w == conditional) CompatibilityVerdict.CONDITIONAL else CompatibilityVerdict.INCOMPATIBLE),
            verdict(w, a, CompatibilityVerdict.COMPATIBLE), verdict(w, b, CompatibilityVerdict.COMPATIBLE),
        ) } + verdict(request, a, CompatibilityVerdict.COMPATIBLE)
        val output = AnalysisOutput(analyzed, verdicts, Proposal(chosen.id, emptyMap(), emptyList()), null,
            emptyList(), emptyList(), emptyList(), listOf(chosen.id, a.id, b.id))
        val result = nextBatch(output, listOf(b, chosen, a), wishes)
        assertEquals(listOf("A", "B"), result.candidates.map { it.option.name })
        assertEquals(listOf(2, 2), result.candidates.map { it.evidence.size })
        assertEquals(listOf("small print"), result.candidates[0].evidence.single { it.wishId == request.id }.quotes)
        assertTrue(result.candidates[0].evidence.single { it.wishId == ungrounded.id }.quotes.isEmpty())
        assertFalse(nextBatch(output.copy(proposal = null), listOf(chosen, a), wishes).hasProposal)
        assertTrue(nextBatch(output.copy(verdicts = emptyList()), listOf(chosen, a), wishes).candidates.isEmpty())
    }
}

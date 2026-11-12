package app.campaign.boundary

import app.campaign.analysisrun.StoredAnalysisRun
import app.campaign.model.AnalysisRun
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.WishKind
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalysisValidationFlag
import app.campaign.port.AnalysisValidationRule
import app.campaign.port.AnalyzedWish
import app.campaign.review.WishReviewOverride
import org.junit.jupiter.api.Test
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class BoundarySummaryTests {
    private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val other = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val time = Instant.parse("2026-11-12T00:00:00Z")
    private val output = AnalysisOutput(
        wishes = listOf(AnalyzedWish(id, WishKind.REQUEST, emptyList()), AnalyzedWish(id, WishKind.REQUEST, emptyList())),
        verdicts = emptyList(), proposal = null, noProposalReason = null, notServed = emptyList(),
        questionsForMaker = emptyList(), alternatives = emptyList(), optionIds = emptyList(),
    )
    private fun stored(flags: List<AnalysisValidationFlag> = emptyList(), seed: Boolean = true): StoredAnalysisRun {
        val run = AnalysisRun(id, "<model>", "hash", "{}", isSeed = seed, createdAt = time, id = other)
        return StoredAnalysisRun(run, output, flags, 2)
    }
    private fun flag(rule: AnalysisValidationRule) = AnalysisValidationFlag(rule, "wishes[$id]", "private input")

    @Test
    fun `counts original distinct wishes and groups flags without inventing removals`() {
        val original = stored(listOf(flag(AnalysisValidationRule.V2), flag(AnalysisValidationRule.V3),
            flag(AnalysisValidationRule.V6), flag(AnalysisValidationRule.UNANALYZED_WISH)))
        val override = WishReviewOverride(id, other, id, other, CompatibilityVerdict.COMPATIBLE, null, "reason", time, 1)
        val summary = summarizeBoundary(original.copy(output = output.copy(wishes = emptyList()), overrides = listOf(override)))!!
        assertEquals(1, summary.representedWishCount)
        assertEquals(2, summary.newWishCount)
        assertEquals(1, summary.overrideCount)
        assertEquals(4, summary.flags.sumOf { it.count })
        assertContains(summary.flags.single { it.label.contains("V6") }.meaning, "rebuilt")
        assertEquals(1, summary.flags.single { it.label == "Omitted wish" }.count)
        assertNull(summarizeBoundary(null))
        val legacy = summarizeBoundary(stored())!!
        assertEquals(true, legacy.seed)
        assertEquals(emptyList(), legacy.flags)
        assertEquals(1, legacy.representedWishCount)
    }

    @Test
    fun `fragment escapes model and shows actual run values and honest validation labels`() {
        val resolver = ClassLoaderTemplateResolver().apply { prefix = "templates/"; suffix = ".html"; characterEncoding = "UTF-8" }
        val engine = SpringTemplateEngine().apply { setTemplateResolver(resolver) }
        val context = Context().apply { setVariable("summary", summarizeBoundary(stored(listOf(
            flag(AnalysisValidationRule.V2), flag(AnalysisValidationRule.V2), flag(AnalysisValidationRule.V6),
        ), seed = false))) }
        val html = engine.process("boundary/panel", setOf("panel"), context)
        assertContains(html, "&lt;model&gt;")
        assertContains(html, time.toString())
        assertContains(html, other.toString())
        assertContains(html, "Ungrounded quote (V2)")
        assertContains(html, "Regrouping (V6)")
        assertContains(html, "attempted or read count is unavailable")
        assertFalse(html.contains("0 rejected"))
        assertFalse(html.contains("private input"))
        context.setVariable("summary", null)
        assertContains(engine.process("boundary/panel", setOf("panel"), context), "No analysis run is available")
    }
}

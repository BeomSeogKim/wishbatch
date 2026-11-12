package app.web.maker

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.model.AnalysisRun
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Money
import app.campaign.model.NeedAttribute
import app.campaign.model.NeedStrength
import app.campaign.model.WishKind
import app.campaign.port.AnalysisFailure
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalysisResult
import app.campaign.port.AnalysisValidationFlag
import app.campaign.port.AnalysisValidationResult
import app.campaign.port.AnalysisValidationRule
import app.campaign.port.AnalyzedWish
import app.campaign.port.MakerQuestion
import app.campaign.port.NotServedGroup
import app.campaign.port.Proposal
import app.campaign.port.ProposalEvidence
import app.campaign.port.WishNeed
import app.campaign.port.WishVerdict
import app.campaign.rules.analysisInputHash
import app.campaign.rules.optionWishCounts
import app.campaign.workspace.WorkspaceService
import app.testing.InMemoryWishAnalyzer
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(ProposalControllerTests.Configuration::class)
@Transactional
class ProposalControllerTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val workspaces: WorkspaceService,
    @Autowired private val runs: AnalysisRunService,
    @Autowired private val analyzer: InMemoryWishAnalyzer,
    @Autowired private val entityManager: EntityManager,
    @Autowired private val mapper: JsonMapper,
) {
    @TestConfiguration
    class Configuration {
        @Bean
        @Primary
        fun proposalAnalyzer() = InMemoryWishAnalyzer()
    }

    @BeforeEach
    fun resetScript() {
        analyzer.results.clear()
    }

    @Test
    fun `fresh workspace renders stored evidence and catalog without a live call`() {
        val before = analyzer.calls.size
        val workspace = workspaces.create()
        val stored = assertNotNull(runs.latest(workspace.token))
        val html = page(workspace.token)
        assertEquals(before, analyzer.calls.size)
        assertContains(html, "Sandbox demo. No real money moves.")
        assertContains(html, "Stored seed run")
        assertContains(html, stored.run.model)
        assertContains(html, "datetime=\"${stored.run.createdAt}\"")
        assertContains(html, ">${MakerDisplay().time(stored.run.createdAt)}</time>")
        assertContains(html, "USD $6.50 / unit")
        assertContains(html, "3 units")
        assertContains(html, "10 units")
        assertContains(html, "2 weeks")
        assertContains(html, "Use this proposal")
        assertContains(html, "/w/${workspace.token}/maker/offer?run=${stored.run.id}")
        assertContains(html, "stated interest, not orders")
        assertContains(html, "Not a request")
        assertContains(html, "Validation items dropped or flagged")
        assertEquals(1, Regex("<h1\\b").findAll(html).count())
        assertEquals(1, Regex("button button-primary").findAll(html).count())
        assertFalse(html.contains("<progress"))
        for (evidence in stored.output.proposal!!.rationale) {
            assertContains(html, "href=\"#wish-${evidence.wishId}\"")
            assertContains(html, "id=\"wish-${evidence.wishId}\"")
        }
        val counts = optionWishCounts(stored.output).single { it.optionId == stored.output.proposal!!.optionId }
        for ((group, count) in listOf("compatible" to counts.compatible, "depends" to counts.depends,
            "not-served" to counts.notServed, "not-a-request" to counts.notARequest)) {
            assertContains(html, "href=\"#$group\"><strong>$count</strong>")
            assertContains(html, "id=\"$group\"")
        }
        for (wishId in stored.output.questionsForMaker.flatMap { it.wishIds }) {
            assertContains(html, "href=\"#wish-$wishId\"")
        }
    }

    @Test
    fun `offer evidence links retain the selected analysis after a newer run`() {
        val token = workspaces.create().token
        val original = assertNotNull(runs.latest(token))
        store(token, original.output.copy(proposal = null, noProposalReason = "No newer proposal.", notServed = emptyList()))
        val before = analyzer.calls.size

        val html = mvc.get("/w/$token/maker/proposal") {
            param("run", original.run.id.toString())
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString

        assertContains(html, "Stored seed run")
        assertContains(html, original.run.model)
        assertContains(html, "offer?run=${original.run.id}")
        assertFalse(html.contains("No newer proposal."))
        assertContains(page(token), "No newer proposal.")
        assertEquals(before, analyzer.calls.size)
    }

    @Test
    fun `selected analysis must exist in the requested workspace`() {
        val token = workspaces.create().token
        val foreign = assertNotNull(runs.latest(workspaces.create().token)).run.id
        val before = analyzer.calls.size
        for (run in listOf(foreign, UUID.randomUUID())) {
            mvc.get("/w/$token/maker/proposal") {
                param("run", run.toString())
            }.andExpect {
                status { isNotFound() }
                view { name("error/not-found") }
            }
        }
        assertEquals(before, analyzer.calls.size)
    }

    @Test
    fun `all typed failures redirect and retain the previous evidence`() {
        val token = workspaces.create().token
        val original = assertNotNull(runs.latest(token))
        val messages = mapOf(
            AnalysisFailure.NOT_CONFIGURED to "Live analysis is not configured.",
            AnalysisFailure.TIMEOUT to "The model took too long to respond.",
            AnalysisFailure.INVALID_JSON to "The model returned a response we could not read.",
            AnalysisFailure.INVALID_OUTPUT to "The model response did not pass the evidence checks.",
            AnalysisFailure.PROVIDER_FAILURE to "The model service is unavailable.",
            AnalysisFailure.RUN_LIMIT_REACHED to "The analysis limit has been reached",
        )
        for ((failure, message) in messages) {
            analyzer.results.add(AnalysisResult.Failure(failure))
            val result = mvc.post("/w/$token/maker/proposal").andExpect {
                status { is3xxRedirection() }
                redirectedUrl("/w/$token/maker/proposal")
            }.andReturn()
            val html = mvc.get("/w/$token/maker/proposal") {
                flashAttr("analysisError", result.flashMap["analysisError"]!!)
            }.andExpect { status { isOk() } }.andReturn().response.contentAsString
            assertContains(html, message)
            assertContains(html, "role=\"alert\"")
            assertContains(html, "last valid analysis is still shown")
            assertContains(html, original.run.model)
            assertContains(html, "offer?run=${original.run.id}")
            assertEquals(original.run.id, runs.latest(token)!!.run.id)
        }
    }

    @Test
    fun `no proposal gives its reason and no offer action`() {
        val token = workspaces.create().token
        val original = runs.latest(token)!!.output
        store(token, original.copy(proposal = null, noProposalReason = "Only large wall posters were requested.", notServed = emptyList()))
        val html = page(token)
        assertContains(html, "No feasible batch")
        assertContains(html, "Only large wall posters were requested.")
        assertFalse(html.contains("Use this proposal"))
        assertFalse(html.contains("href=\"/w/$token/maker/offer"))
        assertContains(html, "How every option compares.")
    }

    @Test
    fun `new wish notice and no visible wishes state are explicit`() {
        val token = workspaces.create().token
        assertContains(page(token), "<strong>0</strong> visible wishes added")
        workspaces.addWish(token, "Please include a fox.")
        assertContains(page(token), "<strong>1</strong> visible wishes added")
        assertContains(page(token), "Re-run to include their words")
        workspaces.listWishes(token).forEach { workspaces.setWishHidden(token, it.id, true) }
        val html = page(token)
        assertContains(html, "No visible wishes to analyze.")
        assertTrue(Regex("<button[^>]*id=\"analysis-submit\"[^>]*disabled").containsMatchIn(html))
        val before = analyzer.calls.size
        val result = mvc.post("/w/$token/maker/proposal").andExpect { status { is3xxRedirection() } }.andReturn()
        assertContains(result.flashMap["analysisError"].toString(), "no visible wishes")
        assertEquals(before, analyzer.calls.size)
    }

    @Test
    fun `live success posts redirects and exposes a clear waiting state`() {
        val token = workspaces.create().token
        val original = assertNotNull(runs.latest(token))
        analyzer.results.add(AnalysisResult.Success("test-live-model", AnalysisValidationResult.Accepted(original.output, emptyList()),
            "replaced-by-fake", Instant.parse("2090-01-01T00:00:00Z")))
        val result = mvc.post("/w/$token/maker/proposal").andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/w/$token/maker/proposal")
        }.andReturn()
        assertContains(result.flashMap["analysisNotice"].toString(), "Analysis complete")
        val html = page(token)
        assertContains(html, "test-live-model")
        assertContains(html, "Live analysis")
        assertContains(html, "Reading wishes and checking evidence")
        assertContains(html, "about thirty seconds")
        assertContains(html, "addEventListener('submit'")
        assertContains(html, "setAttribute('aria-busy', 'true')")
        assertContains(html, "document.getElementById('analysis-submit').disabled = true")
    }

    @Test
    fun `commercial values and counts come from catalog and verdicts rather than extracted fan numbers`() {
        val token = workspaces.create().token
        val options = workspaces.listOptions(token)
        val option = options.single { it.format == "postcard-set" }
        val wish = workspaces.addWish(token, "<b>I want cards.</b>", "<i>Fan</i>")
        val output = AnalysisOutput(
            wishes = listOf(AnalyzedWish(wish.id, WishKind.REQUEST,
                listOf(
                    WishNeed(NeedStrength.HARD, NeedAttribute.FORMAT, "Cards", "I want cards."),
                    WishNeed(NeedStrength.PREFERENCE, NeedAttribute.SUBJECT, "Cards", "I want cards."),
                    WishNeed(NeedStrength.UNKNOWN, NeedAttribute.OTHER, "Unspecified designs", "I want cards."),
                ),
                maxUnitPrice = Money(987654321, "USD"), quantity = 456789, region = "US")),
            verdicts = options.map { WishVerdict(wish.id, it.id,
                if (it.id == option.id) CompatibilityVerdict.COMPATIBLE else CompatibilityVerdict.INCOMPATIBLE,
                if (it.id == option.id) "The requested format is available." else "Requires cards.") },
            proposal = Proposal(option.id, mapOf("finish" to "matte"),
                listOf(ProposalEvidence("A fan explicitly asks for cards.", wish.id, "I want cards."))),
            noProposalReason = null,
            notServed = emptyList(),
            questionsForMaker = listOf(MakerQuestion("Which designs will you include?", listOf(wish.id))),
            alternatives = emptyList(),
            optionIds = options.map { it.id },
        )
        store(token, output, listOf(AnalysisValidationFlag(AnalysisValidationRule.V3, "price", "Commercial term discarded")))
        val html = page(token)
        assertContains(html, "USD $6.50 / unit")
        assertContains(html, "3 units")
        assertContains(html, "10 units")
        assertFalse(html.contains("987654321"))
        assertFalse(html.contains("9,876,543.21"))
        assertFalse(html.contains("456789"))
        assertContains(html, "&lt;b&gt;I want cards.&lt;/b&gt;")
        assertContains(html, "&lt;i&gt;Fan&lt;/i&gt;")
        assertContains(html, "Hard condition")
        assertContains(html, "Preference")
        assertContains(html, "Unknown")
        assertContains(html, "Validation items dropped or flagged: 1")
        for (counts in optionWishCounts(output)) {
            val row = Regex("<tr>\\s*<th scope=\"row\">${Regex.escape(options.single { it.id == counts.optionId }.name)}</th>(.*?)</tr>",
                RegexOption.DOT_MATCHES_ALL).find(html)!!.groupValues[1]
            assertEquals(listOf(counts.compatible, counts.depends, counts.notServed, counts.notARequest),
                Regex("<td>(\\d+)</td>").findAll(row).map { it.groupValues[1].toInt() }.toList())
        }
    }

    @Test
    fun `not served wishes are grouped by their conflict reason`() {
        val token = workspaces.create().token
        val output = runs.latest(token)!!.output
        val incompatible = output.verdicts.filter { it.optionId == output.proposal!!.optionId && it.verdict == CompatibilityVerdict.INCOMPATIBLE }
        val group = NotServedGroup("Requires another format", incompatible.map { it.wishId }, "The requested product format is not a postcard.")
        store(token, output.copy(notServed = listOf(group)))
        val html = page(token)
        assertContains(html, group.label)
        assertContains(html, group.reason)
        for (verdict in incompatible) assertContains(html, "id=\"wish-${verdict.wishId}\"")
    }

    @Test
    fun `catalog formats attributes and variants render as human labels`() {
        for ((format, label) in mapOf("postcard-set" to "Postcard set", "art-print" to "Art print",
            "sticker-sheet" to "Sticker sheet")) {
            val token = workspaces.create().token
            val option = workspaces.listOptions(token).single { it.format == format }
            val output = runs.latest(token)!!.output
            val variants = option.allowedVariants.mapValues { it.value.first() }
            store(token, output.copy(proposal = Proposal(option.id, variants, emptyList()), notServed = emptyList()))
            val html = page(token)
            assertContains(html, "<p>$label</p>")
            assertFalse(html.contains(format))
            if (format == "sticker-sheet") {
                assertContains(html, "<dt>Cut style</dt><dd>Die cut</dd>")
            } else {
                assertContains(html, "<dt>Size</dt><dd>")
                assertContains(html, "<dt>Designs in a set</dt><dd>")
                assertContains(html, "<dt>Suggested ${if (format == "postcard-set") "Finish" else "Paper"}</dt><dd>Matte</dd>")
            }
            assertFalse(html.contains("designCount"))
            assertFalse(html.contains("die-cut"))
        }
    }

    @Test
    fun `wish groups preview three and disclose the rest while preserving conflict reasons`() {
        for (size in listOf(0, 3, 6)) {
            val token = workspaces.create().token
            val output = runs.latest(token)!!.output
            val optionId = output.proposal!!.optionId
            val wishes = workspaces.listWishes(token).take(size * 4)
            val groups = listOf("compatible", "depends", "not-served", "not-a-request")
                .mapIndexed { index, name -> name to wishes.drop(index * size).take(size) }.toMap()
            val notServed = groups.getValue("not-served")
            val reasonGroups = listOf(
                NotServedGroup("Different format", notServed.take(2).map { it.id }, "Requires another format."),
                NotServedGroup("Different size", notServed.drop(2).map { it.id }, "Requires another size."),
            ).filter { it.wishIds.isNotEmpty() }
            store(token, output.copy(
                wishes = wishes.map { AnalyzedWish(it.id,
                    if (it in groups.getValue("not-a-request")) WishKind.NOT_A_REQUEST else WishKind.REQUEST, emptyList()) },
                verdicts = listOf("compatible" to CompatibilityVerdict.COMPATIBLE,
                    "depends" to CompatibilityVerdict.CONDITIONAL, "not-served" to CompatibilityVerdict.INCOMPATIBLE)
                    .flatMap { (name, verdict) -> groups.getValue(name).map { WishVerdict(it.id, optionId, verdict, name) } },
                proposal = Proposal(optionId, emptyMap(), emptyList()),
                notServed = reasonGroups, questionsForMaker = emptyList(),
            ))
            val html = page(token)
            for ((name, rows) in groups) {
                val section = html.substringAfter("id=\"$name\"")
                    .substringBefore(if (name == "not-a-request") "About this analysis." else "id=\"${groups.keys.elementAt(groups.keys.indexOf(name) + 1)}\"")
                assertContains(section, "class=\"badge badge-neutral\">$size</span>")
                if (size > 3) {
                    val preview = section.substringBefore("<details")
                    val disclosure = section.substringAfter("<details").substringBefore("</details>")
                    assertEquals(3, Regex("<article\\b").findAll(preview).count())
                    assertEquals(size - 3, Regex("<article\\b").findAll(disclosure).count())
                    assertFalse(disclosure.substringBefore('>').contains("open"))
                    assertContains(disclosure, "Show all $size")
                    rows.take(3).forEach { assertContains(preview, "id=\"wish-${it.id}\"") }
                    rows.drop(3).forEach { assertContains(disclosure, "id=\"wish-${it.id}\"") }
                    if (name == "not-served") {
                        assertContains(preview, "Different format")
                        assertContains(preview, "Different size")
                        assertContains(disclosure, "Different size")
                        assertContains(disclosure, "Requires another size.")
                    }
                } else {
                    assertFalse(section.contains("<details"))
                    assertEquals(size, Regex("<article\\b").findAll(section).count())
                }
            }
            assertContains(html, "aria-label=\"Proposal sections\"")
            assertContains(html, "href=\"#wish-groups\"")
        }
    }

    @Test
    fun `maker proposal card explains what the step shows`() {
        val token = workspaces.create().token
        val html = mvc.get("/w/$token/maker").andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(html, "See the proposed batch, quoted evidence, wish groups, and questions to settle before making an offer.")
    }

    @Test
    fun `unknown tokens use the shared not found response for reads and posts`() {
        for (token in listOf("missing", "x".repeat(43))) {
            mvc.get("/w/$token/maker/proposal").andExpect {
                status { isNotFound() }
                view { name("error/not-found") }
            }
            mvc.post("/w/$token/maker/proposal").andExpect {
                status { isNotFound() }
                view { name("error/not-found") }
            }
        }
    }

    private fun page(token: String): String = mvc.get("/w/$token/maker/proposal").andExpect {
        status { isOk() }
        view { name("maker/proposal") }
    }.andReturn().response.contentAsString

    private fun store(token: String, output: AnalysisOutput, flags: List<AnalysisValidationFlag> = emptyList()) {
        val workspace = workspaces.findByToken(token)!!
        entityManager.persist(AnalysisRun(workspace.id, "test-render-model",
            analysisInputHash(workspaces.listOptions(token), workspaces.listWishes(token)), mapper.writeValueAsString(output),
            flags.map { mapper.writeValueAsString(it) }, createdAt = Instant.parse("2090-01-01T00:00:00Z")))
        entityManager.flush()
    }
}

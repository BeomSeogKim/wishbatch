package app.campaign.workspace

import app.campaign.analysisrun.AnalysisRunService
import app.campaign.analysisrun.SeedAnalysisFixture
import app.campaign.followup.FollowupDrafter
import app.campaign.followup.FollowupDraft
import app.campaign.followup.FollowupQuestions
import app.campaign.followup.FollowupService
import app.campaign.gloss.GlossSource
import app.campaign.gloss.GlossStatus
import app.campaign.gloss.GlossTranslation
import app.campaign.gloss.WishGlossService
import app.campaign.gloss.WishGlossTranslator
import app.campaign.gloss.glossSourceHash
import app.campaign.port.WishAnalyzer
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Option
import app.campaign.model.WishKind
import app.campaign.replies.ReplyDrafter
import app.campaign.replies.ReplySafety
import app.campaign.replies.ReplyService
import app.campaign.rules.analysisInputHash
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.io.ClassPathResource
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PopulatedDemoTests.NoCalls::class)
@Transactional
class PopulatedDemoTests @Autowired constructor(
    private val workspaces: WorkspaceService,
    private val catalog: SeedCatalog,
    private val runs: AnalysisRunService,
    private val followups: FollowupService,
    private val replies: ReplyService,
    private val glosses: WishGlossService,
    private val entities: EntityManager,
    private val mapper: JsonMapper,
) {
    @TestConfiguration(proxyBeanMethods = false)
    class NoCalls {
        @Bean @Primary fun unusedAnalyzer() = WishAnalyzer { _, _ -> unexpectedCall() }
        @Bean @Primary fun unusedFollowup() = FollowupDrafter { _, _ -> unexpectedCall() }
        @Bean @Primary fun unusedReply() = ReplyDrafter { unexpectedCall() }
        @Bean @Primary fun unusedGloss() = WishGlossTranslator { unexpectedCall() }

        companion object {
            val calls = AtomicInteger()
            private fun unexpectedCall(): Nothing {
                calls.incrementAndGet()
                error("Stored demo creation and reads must not call a provider")
            }
        }
    }

    @Test
    fun `synthetic enrichment fixtures hydrate all scenarios into independent workspaces and reload without calls`() {
        val resources = catalog.scenarios.associate { scenario ->
            "${seedPath(scenario.key)}/enrichment.json" to mapper.writeValueAsBytes(syntheticEnrichment(scenario))
        }
        withResources(resources) { assertHydration() }
    }

    @Test
    fun `packaged generated runs and enrichments hydrate without provider calls`() {
        for (scenario in catalog.scenarios) {
            val fixture = read("${seedPath(scenario.key)}/analysis-run.json", SeedAnalysisFixture::class.java)
            assertNotEquals("synthetic-reference", fixture.model)
            assertNotNull(fixture.validated.output.secondReading)
            assertNotNull(fixture.validated.output.metrics)
        }
        assertHydration()
    }

    private fun assertHydration() {
        for (scenario in catalog.scenarios) {
            val path = seedPath(scenario.key)
            val fixture = read("$path/analysis-run.json", SeedAnalysisFixture::class.java)
            val artifact = read("$path/enrichment.json", SeedEnrichment::class.java)
            assertEquals(fixture.inputHash, artifact.inputHash)
            val first = workspaces.create(scenario.key)
            val second = workspaces.create(scenario.key)
            entities.flush()
            entities.clear()
            val scopedIds = mutableListOf<Set<java.util.UUID>>()
            for (workspace in listOf(first, second)) {
                val stored = assertNotNull(runs.latest(workspace.token))
                val wishes = workspaces.listWishes(workspace.token)
                val options = workspaces.listOptions(workspace.token)
                val wishIds = wishes.map { it.id }.toSet()
                val optionIds = options.map { it.id }.toSet()
                scopedIds += wishIds
                assertTrue(stored.run.isSeed)
                assertEquals(fixture.model, stored.run.model)
                assertEquals(fixture.completedAt, stored.run.createdAt)
                assertEquals(fixture.validated.flags.map { flag ->
                    if (flag.rule.name == "INCOMPLETE_SECOND_READING") {
                        val originalId = java.util.UUID.fromString(flag.path.removePrefix("wishes[").removeSuffix("]"))
                        flag.copy(path = "wishes[${catalog.wishId(workspace.id, fixture.wishKeys.getValue(originalId))}]")
                    } else flag
                }, stored.flags)
                assertEquals(fixture.validated.output.metrics, stored.output.metrics)
                assertEquals(analysisInputHash(options, wishes), stored.run.inputHash)
                assertEquals(0, stored.newWishCount)
                val originalReading = fixture.validated.output.secondReading
                if (originalReading == null) assertNull(stored.output.secondReading) else {
                    val reading = assertNotNull(stored.output.secondReading)
                    assertEquals(originalReading.agreedWishCount, reading.agreedWishCount)
                    val unpaired = originalReading.incompleteWishIds.map {
                        catalog.wishId(workspace.id, fixture.wishKeys.getValue(it))
                    }.toSet()
                    val paired = wishIds - unpaired
                    assertEquals(originalReading.status, reading.status)
                    assertEquals(unpaired, reading.incompleteWishIds.toSet())
                    assertEquals(paired, reading.comparisons.map { it.wishId }.toSet())
                    assertEquals(if (paired.isEmpty()) emptySet() else optionIds, reading.comparisons.map { it.optionId }.toSet())
                    assertEquals(paired.size * optionIds.size, reading.comparisons.size)
                    assertEquals(paired.size, reading.comparedWishCount)
                    assertEquals(reading.comparedWishCount - reading.comparisons.filter { it.needsYourEyes }
                        .map { it.wishId }.distinct().size, reading.agreedWishCount)
                    assertEquals(originalReading.batchCompositionChanged, reading.batchCompositionChanged)
                }
                val sources = wishes.associateBy { it.id }
                stored.output.wishes.forEach { wish ->
                    wish.needs.forEach { need -> assertTrue(normalize(sources.getValue(wish.wishId).text).contains(normalize(need.quote))) }
                }
                stored.output.proposal?.rationale?.forEach { item ->
                    assertTrue(normalize(sources.getValue(item.wishId).text).contains(normalize(item.quote)))
                }
                val questions = followups.makerPage(workspace.token)
                assertTrue(questions.drafted)
                assertEquals(artifact.followup.failure, questions.failure)
                assertEquals(artifact.followup.drafts.size, questions.questions.size)
                questions.questions.forEach { question ->
                    assertEquals(workspace.id, question.workspaceId)
                    assertEquals(stored.run.id, question.runId)
                    assertTrue(question.optionId in optionIds && question.wishIds.all { it in wishIds })
                    assertFalse(question.approved)
                    assertEquals(artifact.followup.completedAt, question.createdAt)
                }
                wishes.forEach { wish -> assertTrue(followups.fanQuestions(workspace.token, wish.id).isEmpty()) }
                val reply = replies.maker(workspace.token, stored.run.id)
                assertEquals(if (reply.groups.isEmpty()) "EMPTY" else artifact.replies.failure ?: "DRAFTED", reply.status)
                assertEquals(artifact.replies.bodies, reply.groups.mapNotNull { it.draft })
                reply.groups.forEach { group ->
                    assertFalse(group.approved)
                    group.evidence.sources.forEach { source ->
                        assertEquals(sources.getValue(source.wishId).text, source.quote)
                        assertNull(replies.approvedForWish(workspace.token, stored.run.id, source.wishId))
                    }
                }
                val aids = glosses.read(workspace.token, stored.run.id)
                val savedAids = aids.rows.mapNotNull { it.saved }
                assertEquals(artifact.gloss.sources.size, savedAids.size)
                assertTrue(aids.rows.all { it.current })
                savedAids.forEach { aid ->
                    assertEquals(workspace.id, aid.workspaceId)
                    assertEquals(glossSourceHash(sources.getValue(aid.wishId).text), aid.sourceHash)
                    val key = scenario.wishes.single { catalog.wishId(workspace.id, it.key) == aid.wishId }.key
                    val originalId = fixture.wishKeys.entries.single { it.value == key }.key
                    val translation = artifact.gloss.translations.singleOrNull { it.wishId == originalId }
                    assertEquals(translation?.status, aid.status)
                    assertEquals(translation?.englishGloss, aid.englishGloss)
                    assertEquals(translation?.inferredLanguage, aid.inferredLanguage)
                    assertEquals(artifact.gloss.model, aid.model)
                    assertEquals(if (translation == null) null else artifact.gloss.generatedAtByWishId[originalId] ?: artifact.gloss.completedAt, aid.generatedAt)
                    assertEquals(1, aid.attempts)
                }
                assertEquals(stored.originalOutput, assertNotNull(runs.latest(workspace.token)).originalOutput)
            }
            assertTrue(scopedIds[0].intersect(scopedIds[1]).isEmpty())
            val firstRun = assertNotNull(runs.latest(first.token))
            val secondRun = assertNotNull(runs.latest(second.token))
            assertNotEquals(firstRun.run.id, secondRun.run.id)
            assertFailsWith<WorkspaceNotFoundException> { replies.maker(first.token, secondRun.run.id) }
            assertFailsWith<WorkspaceNotFoundException> { glosses.read(first.token, secondRun.run.id) }
        }
        assertFailsWith<WorkspaceNotFoundException> { workspaces.listWishes("missing") }
        assertEquals(0, NoCalls.calls.get())
    }

    @Test
    fun `a mismatching synthetic enrichment hash cannot create a populated demo`() {
        val artifact = syntheticEnrichment(catalog.scenario("illustrator"))
        val replaced = mapper.writeValueAsBytes(artifact.copy(inputHash = "0".repeat(64)))
        withResource("seed/enrichment.json", replaced) {
            assertFailsWith<IllegalArgumentException> { workspaces.create() }
        }
    }

    @Test
    fun `a synthetic reply failure cannot impersonate a drafted batch`() {
        val artifact = syntheticEnrichment(catalog.scenario("illustrator"))
        val replaced = mapper.writeValueAsBytes(artifact.copy(replies = artifact.replies.copy(
            bodies = emptyList(), model = null, failure = "DRAFTED",
        )))
        withResource("seed/enrichment.json", replaced) {
            assertFailsWith<IllegalArgumentException> { workspaces.create() }
        }
    }

    @Test
    fun `a changed synthetic reading aid source cannot hydrate into the current seed`() {
        val artifact = syntheticEnrichment(catalog.scenario("illustrator"))
        assertTrue(artifact.gloss.sources.isNotEmpty())
        val sources = artifact.gloss.sources.mapIndexed { index, source ->
            if (index == 0) source.copy(text = "A different original source", sourceHash = glossSourceHash("A different original source")) else source
        }
        val replaced = mapper.writeValueAsBytes(artifact.copy(gloss = artifact.gloss.copy(sources = sources)))
        withResource("seed/enrichment.json", replaced) {
            assertFailsWith<IllegalArgumentException> { workspaces.create() }
        }
    }

    @Test
    fun `cached reading aids retain their original per-source generation times`() {
        val artifact = syntheticEnrichment(catalog.scenario("illustrator"))
        val source = artifact.gloss.sources.first()
        val originalTime = artifact.gloss.completedAt.minusSeconds(60)
        val enriched = artifact.copy(gloss = artifact.gloss.copy(generatedAtByWishId = mapOf(source.wishId to originalTime)))
        withResource("seed/enrichment.json", mapper.writeValueAsBytes(enriched)) {
            val workspace = workspaces.create()
            val run = assertNotNull(runs.latest(workspace.token))
            val localId = catalog.wishId(workspace.id, read("seed/analysis-run.json", SeedAnalysisFixture::class.java).wishKeys.getValue(source.wishId))
            val saved = glosses.read(workspace.token, run.run.id).rows.single { it.wishId == localId }.saved
            assertEquals(originalTime, assertNotNull(saved).generatedAt)
        }
    }

    private fun <T> read(path: String, type: Class<T>): T = ClassPathResource(path).inputStream.use { mapper.readValue(it, type) }

    private fun seedPath(scenario: String) = if (scenario == "illustrator") "seed" else "seed/$scenario"

    private fun normalize(text: String) = text.replace(Regex("(?U)\\s+"), " ").trim()

    private fun syntheticEnrichment(scenario: SeedScenario): SeedEnrichment {
        val fixture = read("${seedPath(scenario.key)}/analysis-run.json", SeedAnalysisFixture::class.java)
        val output = fixture.validated.output
        val seed = scenario.definition.options.single { option -> fixture.optionKeys[output.proposal?.optionId] == option.key }
        val option = Option(fixture.seedWorkspaceId, seed.name, seed.format, seed.fixedAttributes, seed.allowedVariants,
            seed.unitCost, seed.currency, seed.minBatch, seed.maxCapacity, seed.leadTimeText, seed.shipRegion,
            fixture.optionKeys.entries.single { it.value == seed.key }.key)
        val drafts = option.allowedVariants.entries.mapNotNull { (key, choices) ->
            val attribute = FollowupQuestions.attributeForVariantKey(key) ?: return@mapNotNull null
            if (choices.distinct().size < 2) return@mapNotNull null
            val candidates = output.wishes.filter { wish ->
                wish.kind == WishKind.REQUEST && wish.needs.any { FollowupQuestions.matchesVariantNeed(option, key, it) } &&
                    output.verdicts.any { it.wishId == wish.wishId && it.optionId == option.id && it.verdict == CompatibilityVerdict.CONDITIONAL }
            }.map { it.wishId }
            if (candidates.isEmpty()) null else FollowupDraft("Which $key would work for your wish?", attribute, key, choices, candidates)
        }.distinctBy { it.attribute }.take(3)
        FollowupQuestions.validate(drafts, option, output)
        val safeReplies = output.notServed.all { ReplySafety.isSafeReason(it.reason) }
        val bodies = if (safeReplies) output.notServed.map { ReplySafety.body(it.reason) } else emptyList()
        val sources = scenario.wishes.filter { wish -> wish.text.any { it.code > 127 } }.map { wish ->
            GlossSource(fixture.wishKeys.entries.single { it.value == wish.key }.key, glossSourceHash(wish.text), wish.text)
        }
        val translated = sources.map { GlossTranslation(it.wishId, it.sourceHash, GlossStatus.TRANSLATED, "ja", "Synthetic test reading aid.") }
        val completedAt = fixture.completedAt.plusSeconds(1)
        val model = "synthetic-enrichment-test"
        return SeedEnrichment(fixture.inputHash, completedAt, SeedFollowup(drafts, model, null, completedAt),
            SeedReplies(bodies, if (safeReplies) model else null, if (safeReplies) null else "UNSAFE_REASON", completedAt),
            SeedGloss(sources, translated, model, null, completedAt))
    }

    private fun withResource(path: String, bytes: ByteArray, action: () -> Unit) = withResources(mapOf(path to bytes), action)

    private fun withResources(resources: Map<String, ByteArray>, action: () -> Unit) {
        val thread = Thread.currentThread()
        val original = thread.contextClassLoader
        thread.contextClassLoader = object : ClassLoader(original) {
            override fun getResource(name: String): java.net.URL? =
                if (name in resources) super.getResource(name.replace("enrichment.json", "analysis-run.json")) else super.getResource(name)

            override fun getResourceAsStream(name: String): java.io.InputStream? =
                resources[name]?.let(::ByteArrayInputStream) ?: super.getResourceAsStream(name)
        }
        try { action() } finally { thread.contextClassLoader = original }
    }
}

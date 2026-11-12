package app.web.maker

import app.campaign.model.AnalysisRun
import app.campaign.store.AnalysisRunRepository
import app.campaign.store.OfferRepository
import app.campaign.workspace.WorkspaceService
import app.web.security.securePost
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
class OfferFormExperienceTests @Autowired constructor(
    private val mvc: MockMvc,
    private val workspaces: WorkspaceService,
    private val analyses: AnalysisRunRepository,
    private val offers: OfferRepository,
    private val mapper: ObjectMapper,
) {
    @Test
    fun `decimal checks return exact server amounts without interpreting integers as minor units`() {
        val fixture = fixture()
        for ((input, amount) in listOf("6" to 600, "6.5" to 650, "6.50" to 650, " 6.50 " to 650, "650" to 65000)) {
            mvc.get("/w/${fixture.workspace.token}/maker/offer/price-check") {
                param("run", fixture.run.id.toString())
                param("unitPrice", input)
            }.andExpect {
                status { isOk() }
                jsonPath("$.unitPrice") { value(amount) }
                jsonPath("$.belowCost") { value(amount.toLong() < fixture.option.unitCost) }
                jsonPath("$.formattedPrice") { value("USD ${amount / 100}.${(amount % 100).toString().padStart(2, '0')}") }
            }
        }
        assertTrue(offers.findByWorkspaceId(fixture.workspace.id).isEmpty())
    }

    @Test
    fun `malformed decimal checks fail and preserve form entries without publishing`() {
        val fixture = fixture()
        for (input in listOf("0", ".5", "6.", "+6", "6,50", "6e2", "6.501", "92233720368547758.08")) {
            mvc.get("/w/${fixture.workspace.token}/maker/offer/price-check") {
                param("run", fixture.run.id.toString())
                param("unitPrice", input)
            }.andExpect { status { isBadRequest() } }
            val result = mvc.securePost("/w/${fixture.workspace.token}/maker/offer") {
                (validFields(fixture) + mapOf("action" to "check-price", "unitPrice" to input,
                    "specText" to "Keep edited specification")).forEach { (key, value) -> param(key, value) }
            }.andExpect { status { is3xxRedirection() } }.andReturn()
            assertContains(result.flashMap["errors"].toString(), "positive unit price")
            val retained = result.flashMap["fields"] as Map<*, *>
            assertEquals(input, retained["unitPrice"])
            assertEquals("Keep edited specification", retained["specText"])
        }
        assertTrue(offers.findByWorkspaceId(fixture.workspace.id).isEmpty())
    }

    @Test
    fun `publication stores decimal price as exact minor units after explicit terms confirmation`() {
        val fixture = fixture()
        mvc.securePost("/w/${fixture.workspace.token}/maker/offer") {
            (validFields(fixture) + mapOf("action" to "publish", "unitPrice" to "6.50"))
                .forEach { (key, value) -> param(key, value) }
        }.andExpect { redirectedUrl("/w/${fixture.workspace.token}/maker/campaign") }
        assertEquals(650L, offers.findByWorkspaceId(fixture.workspace.id).single().unitPrice)
    }

    @Test
    fun `rendered form keeps safe enter action exact preview questions and compact demand`() {
        val fixture = fixture()
        val html = mvc.get("/w/${fixture.workspace.token}/maker/offer") { param("run", fixture.run.id.toString()) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertContains(html, "value=\"16.00\"")
        assertContains(html, "USD 16.00 per unit")
        assertContains(html, "inputmode=\"decimal\"")
        assertContains(html, "Checked unit price")
        assertContains(html, "What finish can you deliver?")
        assertContains(html, "Review before publishing")
        assertContains(html, "only completed payments can fund a batch")
        assertContains(html, "/maker/offer-draft?run=${fixture.run.id}")
        assertContains(html, "/maker/proposal?run=${fixture.run.id}")
        assertContains(html, "demand-compact")
        assertContains(html, "View full price and demand explorer")
        assertEquals(1, Regex("id=\"demand-explorer\"").findAll(html).count())
        val offerForm = html.substringAfter("action=\"/w/${fixture.workspace.token}/maker/offer\"")
        val firstSubmit = Regex("<button[^>]*type=\"submit\"[^>]*>").find(offerForm)!!.value
        assertContains(firstSubmit, "value=\"check-price\"")
    }

    @Test
    fun `offer warns about visible wishes missing from the selected analysis with a protected rerun action`() {
        val workspace = workspaces.create()
        val run = analyses.findByWorkspaceId(workspace.id).single()
        fun page() = mvc.get("/w/${workspace.token}/maker/offer") { param("run", run.id.toString()) }
            .andExpect { status { isOk() } }.andReturn()
        assertEquals(0, page().modelAndView!!.model["unreadWishCount"])
        val wish = workspaces.addWish(workspace.token, "A new request after the stored analysis")
        val result = page()
        assertEquals(1, result.modelAndView!!.model["unreadWishCount"])
        val html = result.response.contentAsString
        assertContains(html, "This analysis does not include all current visible wishes")
        val rerunForm = Regex("<form[^>]*action=\"/w/${workspace.token}/maker/proposal\"[^>]*>.*?</form>",
            RegexOption.DOT_MATCHES_ALL).find(html)!!.value
        assertContains(rerunForm, "method=\"post\"")
        assertContains(rerunForm, "name=\"_csrf\"")
        assertContains(rerunForm, "Re-run analysis")
        mvc.post("/w/${workspace.token}/maker/proposal").andExpect { status { isForbidden() } }
        workspaces.setWishHidden(workspace.token, wish.id, true)
        val hidden = page()
        assertEquals(0, hidden.modelAndView!!.model["unreadWishCount"])
        assertFalse(hidden.response.contentAsString.contains("Re-run analysis"))
        assertTrue(offers.findByWorkspaceId(workspace.id).isEmpty())
    }

    @Test
    fun `published run and decimal price stay pinned after a newer analysis arrives`() {
        val fixture = fixture()
        mvc.securePost("/w/${fixture.workspace.token}/maker/offer") {
            (validFields(fixture) + mapOf("action" to "publish", "unitPrice" to "6.00"))
                .forEach { (key, value) -> param(key, value) }
        }.andExpect { redirectedUrl("/w/${fixture.workspace.token}/maker/campaign") }
        analyses.saveAndFlush(AnalysisRun(fixture.workspace.id, "test-model", "newer-hash", fixture.run.resultJson,
            createdAt = fixture.run.createdAt.plusSeconds(1)))
        val page = mvc.get("/w/${fixture.workspace.token}/maker/offer")
            .andExpect { status { isOk() } }.andReturn()
        assertEquals(fixture.run.id, (page.modelAndView!!.model["analysis"] as AnalysisRun).id)
        assertEquals(true, page.modelAndView!!.model["priceBelowCost"])
        assertContains(page.response.contentAsString, "value=\"6.00\"")
        assertContains(page.response.contentAsString, "USD 6.00 per unit")
        assertEquals(600L, offers.findByWorkspaceId(fixture.workspace.id).single().unitPrice)
    }

    private fun fixture() = createMoneyFixture(workspaces, analyses, mapper, Clock.systemUTC())
}

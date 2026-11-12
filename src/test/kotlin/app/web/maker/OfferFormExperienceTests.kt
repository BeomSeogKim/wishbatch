package app.web.maker

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
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
        assertContains(html, "value=\"6.50\"")
        assertContains(html, "USD 6.50 per unit")
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
        val firstSubmit = Regex("<button[^>]*type=\"submit\"[^>]*>").find(html)!!.value
        assertContains(firstSubmit, "value=\"check-price\"")
    }

    private fun fixture() = createMoneyFixture(workspaces, analyses, mapper, Clock.systemUTC())
}

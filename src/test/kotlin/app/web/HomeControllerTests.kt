package app.web

import app.campaign.workspace.SeedCatalog
import app.web.home.HomeController
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.util.HtmlUtils
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@WebMvcTest(HomeController::class)
@Import(SeedCatalog::class)
class HomeControllerTests(@Autowired private val mvc: MockMvc) {

    @Test
    fun `landing page renders the shared layout and demo entry point`() {
        val html = mvc.get("/").andExpect {
            status { isOk() }
            view { name("home/index") }
            model { attribute("productName", "Wishbatch") }
        }.andReturn().response.contentAsString

        assertContains(html, "Wishbatch")
        assertContains(html, "Sandbox demo. No real money moves.")
        val demoForm = Regex(
            "<form[^>]*action=\"/demo\"[^>]*method=\"post\"[^>]*>.*?<button[^>]*>Start a demo",
            RegexOption.DOT_MATCHES_ALL
        )
        assertTrue(demoForm.containsMatchIn(html))
        assertEquals(3, Regex("href=\"/guide\"[^>]*>[^<]*(?:Read the testing guide|Testing guide)", RegexOption.IGNORE_CASE).findAll(html).count())
        assertContains(html, "href=\"/#how-it-works\"")
        assertContains(html, "id=\"testing\"")
    }

    @Test
    fun `demo selector renders each catalog scenario in the post form`() {
        val html = mvc.get("/").andReturn().response.contentAsString
        val form = html.substringAfter("<form action=\"/demo\"").substringBefore("</form>")
        assertContains(form, "<label for=\"scenario\">Choose a sample maker</label>")
        assertContains(form, "<select id=\"scenario\" name=\"scenario\">")
        val catalog = SeedCatalog()
        assertEquals(listOf("illustrator", "ceramicist", "textile"), catalog.scenarios.map { it.key })
        catalog.scenarios.forEach { scenario ->
            val label = "${scenario.definition.maker.name} — ${scenario.definition.maker.description}"
            assertContains(HtmlUtils.htmlUnescape(form), "value=\"${scenario.key}\">$label</option>")
        }
    }

    @Test
    fun `layout exposes csrf metadata only when available`() {
        val withoutToken = mvc.get("/").andReturn().response.contentAsString
        assertFalse(withoutToken.contains("name=\"_csrf\""))
        assertFalse(withoutToken.contains("name=\"_csrf_header\""))

        val withToken = mvc.get("/") {
            flashAttr("_csrf", mapOf("token" to "test-token", "headerName" to "X-CSRF-TOKEN"))
        }.andReturn().response.contentAsString
        assertContains(withToken, "name=\"_csrf\" content=\"test-token\"")
        assertContains(withToken, "name=\"_csrf_header\" content=\"X-CSRF-TOKEN\"")
    }

    @Test
    fun `landing overview explains the journey and payment counts`() {
        val html = mvc.get("/").andReturn().response.contentAsString
        val overview = html.substringAfter("class=\"hero shell\"").substringBefore("id=\"how-it-works\"")

        listOf(
            "Fans want different things",
            "01 / Listen", "02 / Shape", "03 / Confirm",
            "Stated interest", "Authorized holds", "Paid",
            "Wishes, not orders", "Units held, not paid", "Units with completed payments",
            "Synthetic example", "PayPal places the holds", "captures can fail",
            "refunds and hold releases are attempted", "Timing depends on PayPal and your bank or card"
        ).forEach { assertContains(overview, it) }
    }

    @Test
    fun `styleguide renders the shared components with synthetic examples`() {
        val html = mvc.get("/styleguide").andExpect {
            status { isOk() }
            view { name("home/styleguide") }
        }.andReturn().response.contentAsString

        assertContains(html, "Sandbox demo. No real money moves.")
        val counts = Regex("<section[^>]*aria-label=\"Campaign counts\"[^>]*>(.*?)</section>", RegexOption.DOT_MATCHES_ALL)
            .find(html)!!.groupValues[1]
        val examples = listOf(
            Triple("interest", "Stated interest", 48),
            Triple("authorized", "Authorized holds", 24),
            Triple("paid", "Paid", 12)
        )
        examples.forEach { (kind, label, value) ->
            val count = Regex("<div class=\"count count-$kind\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
                .find(counts)!!.groupValues[1]
            assertContains(count, ">$label</p>")
            assertContains(count, "class=\"count-value\">$value</p>")
            if (kind == "interest") {
                assertFalse(count.contains("<progress"))
            } else {
                assertContains(count, "value=\"$value\"")
                assertContains(count, "max=\"30\"")
                assertContains(count, ">$value of 30</progress>")
                assertEquals(1, Regex("<progress\\b").findAll(count).count())
            }
        }
        assertContains(counts, "Wishes classified as requests. These are not orders.")
        assertEquals(2, Regex("<progress\\b").findAll(counts).count())
        assertEquals(1, Regex("aria-current=\"step\"").findAll(html).count())
        val currentStep = Regex(
            "<li[^>]*aria-current=\"step\"[^>]*>.*?Proposal",
            RegexOption.DOT_MATCHES_ALL
        )
        assertTrue(currentStep.containsMatchIn(html))
        val sentences = listOf(
            "Hold placed. You have not been charged.",
            "Paid.",
            "PayPal is still processing your payment.",
            "Your payment could not be completed. You were not charged.",
            "Hold released by PayPal. When the amount reappears depends on your bank or card.",
            "Refunded. When the money arrives depends on PayPal and your bank or card.",
            "PayPal is processing your refund."
        )
        sentences.forEach { assertContains(html, it) }
        val terms = Regex("<section[^>]*aria-label=\"Payment terms\"[^>]*>(.*?)</section>", RegexOption.DOT_MATCHES_ALL)
            .find(html)!!.groupValues[1]
        assertEquals(6, Regex("<li>").findAll(terms).count())
        listOf(
            "no payment has been captured yet",
            "maker confirms production",
            "bank or card",
            "refunds of completed payments are requested",
            "A refund is not complete until PayPal confirms it",
            "capture will succeed",
            "delivery terms"
        ).forEach {
            assertContains(terms, it)
        }
        assertFalse(html.contains("??pledge."))
        assertFalse(html.contains("??money.term."))
    }
}

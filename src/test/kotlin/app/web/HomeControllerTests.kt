package app.web

import app.web.home.HomeController
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@WebMvcTest(HomeController::class)
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
                assertEquals(1, Regex("<progress\\b").findAll(count).count())
            }
        }
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
            "completed payments are refunded",
            "capture will succeed",
            "delivery terms"
        ).forEach {
            assertContains(terms, it)
        }
        assertFalse(html.contains("??pledge."))
        assertFalse(html.contains("??money.term."))
    }
}

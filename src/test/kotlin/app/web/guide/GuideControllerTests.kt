package app.web.guide

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@WebMvcTest(GuideController::class)
class GuideControllerTests(@Autowired private val mvc: MockMvc) {

    @Test
    fun `guide renders a numbered journey and a demo form in the shared layout`() {
        val html = mvc.get("/guide").andExpect {
            status { isOk() }
            view { name("guide/index") }
            model { attribute("productName", "Wishbatch") }
        }.andReturn().response.contentAsString

        assertContains(html, "Sandbox demo. No real money moves.")
        assertContains(html, "href=\"https://wishbatch.onrender.com/\"")
        assertContains(html, "no installation, account, or credentials are needed to browse")
        assertContains(html, "Only the payment step needs a PayPal sandbox personal account")
        assertContains(html, "first request after idle can take about a minute")
        assertContains(html, "Local-run alternative")
        assertContains(html, "href=\"https://github.com/BeomSeogKim/wishbatch/blob/main/docs/running.md\"")
        assertContains(html, "Local payments require sandbox REST application credentials on the server")
        assertContains(html, "This product only talks to the PayPal sandbox.")
        assertContains(html, "sandbox buyer account given in the submission's testing instructions")
        assertContains(html, "your own PayPal sandbox personal account")
        assertTrue(Regex(
            "<form[^>]*action=\"/demo\"[^>]*method=\"post\"[^>]*>.*?<button[^>]*>Start a demo",
            RegexOption.DOT_MATCHES_ALL,
        ).containsMatchIn(html))

        val steps = Regex("<ol class=\"testing-list\">(.*?)</ol>", RegexOption.DOT_MATCHES_ALL)
            .find(html)!!.groupValues[1]
        assertEquals(8, Regex("<li>").findAll(steps).count())
        val actions = listOf(
            "Start a demo.",
            "Look at the wishes.",
            "Read the stored analysis and evidence.",
            "Confirm the offer.",
            "Open the fan offer page.",
            "Authorize with PayPal: choose quantity 3.",
            "Confirm production as the maker.",
            "See the result.",
        )
        val positions = actions.map { steps.indexOf(it) }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
        assertContains(steps, "Keep the demo minimum at 3 units")
        assertContains(steps, "in a new tab")
        assertContains(steps, "capture can still fail")
        assertContains(steps, "review the capture summary, select the acknowledgment checkbox")
        assertContains(steps, "Update total")
        assertContains(steps, "15 minutes")
        assertContains(html, "Stated interest counts wishes classified as requests")
        assertContains(html, "Authorized holds count offer units with payment authorization")
        assertContains(html, "Paid counts units whose payments have completed")
        assertContains(html, "Start a fresh demo")
        assertContains(html, "second PayPal approval")
        assertContains(html, "Before confirming production")
        assertContains(html, "hold-release outcome")
    }

    @Test
    fun `guide does not accept state changing requests`() {
        mvc.post("/guide").andExpect { status { isMethodNotAllowed() } }
    }
}

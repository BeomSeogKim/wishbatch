package app.web

import app.web.home.HomeController
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertContains

@WebMvcTest(HomeController::class)
@TestPropertySource(properties = ["PRODUCT_NAME=Batch Studio"])
class ProductNameTests(@Autowired private val mvc: MockMvc) {

    @Test
    fun `both pages use the configured product name`() {
        listOf("/", "/styleguide").forEach { path ->
            val html = mvc.get(path).andExpect {
                status { isOk() }
                model { attribute("productName", "Batch Studio") }
            }.andReturn().response.contentAsString
            val header = Regex("<header\\b.*?</header>", RegexOption.DOT_MATCHES_ALL).find(html)!!.value
            assertContains(header, "Batch Studio")
            assertContains(Regex("<title>.*?</title>").find(html)!!.value, "Batch Studio")
        }
    }
}

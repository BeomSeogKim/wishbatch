package app.startup

import app.Application
import app.paypal.PayPalProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StartupTests {
    @Test
    fun `sandbox guard is initialized during application startup without credentials`() {
        application().use { context ->
            assertTrue(context.beanFactory.containsSingleton("payPalProperties"))
            val properties = context.getBean(PayPalProperties::class.java)
            assertEquals(PayPalProperties.SANDBOX_URL, properties.baseUrl)
            assertEquals("", properties.clientId)
            assertEquals("", properties.clientSecret)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "https://api-m.paypal.com",
        "https://api-m.sandbox.paypal.com/",
        "https://api-m.sandbox.paypal.com.evil.example",
    ])
    fun `unsafe PayPal URL fails application startup before any bean lookup`(baseUrl: String) {
        val error = assertThrows<Exception> {
            application(baseUrl).close()
        }
        assertTrue(generateSequence(error as Throwable) { it.cause }.any {
            it is IllegalArgumentException &&
                it.message?.contains("PAYPAL_BASE_URL must be exactly ${PayPalProperties.SANDBOX_URL}") == true
        })
    }

    private fun application(baseUrl: String = PayPalProperties.SANDBOX_URL) =
        SpringApplicationBuilder(Application::class.java)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.main.banner-mode=off",
                "--spring.datasource.url=jdbc:h2:mem:startup-${UUID.randomUUID()};DB_CLOSE_DELAY=0",
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--PAYPAL_BASE_URL=$baseUrl",
                "--PAYPAL_CLIENT_ID=",
                "--PAYPAL_CLIENT_SECRET=",
                "--LLM_API_KEY=",
                "--LLM_SEED_RUN=false",
            )
}

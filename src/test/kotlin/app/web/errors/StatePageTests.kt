package app.web.errors

import app.campaign.port.PaymentGateway
import app.campaign.port.WishAnalyzer
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestComponent
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StatePageTests.Fixtures::class)
class StatePageTests {
    @LocalServerPort private var port = 0
    @MockitoBean private lateinit var payments: PaymentGateway
    @MockitoBean private lateinit var analyzer: WishAnalyzer

    @Test
    fun `missing pages and unexpected errors render safe HTML through error dispatch`() {
        HttpClient.newHttpClient().use { client ->
            for ((path, status) in listOf(
                "/w/missing/maker" to 404,
                "/missing-page" to 404,
                "/state-test/fail?value=synthetic-secret-sentinel" to 500,
            )) {
                val response = get(client, path, "text/html")
                assertEquals(status, response.statusCode())
                assertContains(response.headers().firstValue("content-type").orElse(""), "text/html")
                assertContains(response.body(), "Sandbox demo. No real money moves.")
                assertTrue(Regex("href=\"/(;jsessionid=[A-F0-9]+)?\">Return home</a>").containsMatchIn(response.body()))
                assertFalse(response.body().contains("synthetic-secret-sentinel"))
                assertFalse(response.body().contains("synthetic-exception-sentinel"))
            }
        }
    }

    @Test
    fun `binding failures retain their JSON contract`() {
        HttpClient.newHttpClient().use { client ->
            val response = get(client, "/state-test/number?value=synthetic-secret-sentinel", "application/json")
            assertEquals(400, response.statusCode())
            assertContains(response.headers().firstValue("content-type").orElse(""), "application/json")
            assertContains(response.body(), "This request could not be read.")
            assertFalse(response.body().contains("synthetic-secret-sentinel"))
        }
    }

    private fun get(client: HttpClient, path: String, accept: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI("http://localhost:$port$path")).header("Accept", accept).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @TestConfiguration(proxyBeanMethods = false)
    class Fixtures {
        @Bean fun stateFixture() = StateFixture()
    }
}

@TestComponent
@RestController
class StateFixture {
    @GetMapping("/state-test/fail")
    fun fail(): String = throw IllegalStateException("synthetic-exception-sentinel")

    @GetMapping("/state-test/number")
    fun number(@RequestParam value: Int) = value
}

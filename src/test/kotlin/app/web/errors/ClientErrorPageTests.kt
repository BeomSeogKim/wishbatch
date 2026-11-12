package app.web.errors

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientErrorPageTests {
    @LocalServerPort private var port = 0

    @Test
    fun `client error dispatch renders a safe page`() {
        HttpClient.newHttpClient().use { client ->
            for ((path, status) in listOf("/demo" to 405, "/w/test/offer?quantity=invalid" to 400)) {
                val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                    .header("Accept", "text/html").GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                assertEquals(status, response.statusCode())
                assertContains(response.body(), "Sandbox demo. No real money moves.")
                assertContains(response.body(), "We could not open this page.")
                assertContains(response.body(), "Return home</a>")
                assertFalse(response.body().contains("Whitelabel Error Page"))
                assertFalse(response.body().contains("quantity=invalid"))
            }
        }
    }

    @Test
    fun `csrf rejection negotiates html and json without exposing request details`() {
        HttpClient.newHttpClient().use { client ->
            for (accept in listOf("text/html", "application/json", "*/*")) {
                val request = HttpRequest.newBuilder(URI("http://localhost:$port/demo"))
                    .header("Accept", accept).POST(HttpRequest.BodyPublishers.noBody()).build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                assertEquals(403, response.statusCode())
                assertFalse(response.headers().firstValue("X-Request-ID").orElse("").isBlank())
                if (accept == "text/html") {
                    assertContains(response.headers().firstValue("content-type").orElse(""), "text/html")
                    assertContains(response.body(), "We could not verify this request.")
                    assertContains(response.body(), "Sandbox demo. No real money moves.")
                } else {
                    assertContains(response.headers().firstValue("content-type").orElse(""), "application/json")
                    assertContains(response.body(), "This page expired")
                }
            }
        }
    }
}

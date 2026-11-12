package app.web.fan

import app.campaign.workspace.WorkspaceService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
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
class PublicPageRenderTests @Autowired constructor(
    private val workspaces: WorkspaceService,
) {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `public pages render over HTTP with the in memory application and no campaign provider`() {
        val token = workspaces.create().token
        HttpClient.newHttpClient().use { client ->
            for ((path, heading) in listOf(
                "/guide" to "Testing guide",
                "/w/$token" to "What would you love to see?",
            )) {
                val request = HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                assertEquals(200, response.statusCode())
                assertContains(response.headers().firstValue("content-type").orElse(""), "text/html")
                assertContains(response.body(), "<h1>$heading</h1>")
                assertContains(response.body(), "Sandbox demo. No real money moves.")
                assertFalse(response.body().contains("th:replace"))
                assertFalse(response.body().contains("href=\"/w/$token/offer\""))
            }
        }
    }
}

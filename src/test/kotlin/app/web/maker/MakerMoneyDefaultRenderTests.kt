package app.web.maker

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
class MakerMoneyDefaultRenderTests @Autowired constructor(private val workspaces: WorkspaceService) {
    @LocalServerPort private var port = 0

    @Test
    fun `maker pages render over HTTP on the default in memory application without credentials`() {
        val workspace = workspaces.create()
        HttpClient.newHttpClient().use { client ->
            for ((route, heading) in listOf("offer" to "Make the offer exact.", "campaign" to "Follow the commitments.",
                "result" to "What happened to the batch?")) {
                val request = HttpRequest.newBuilder(URI("http://localhost:$port/w/${workspace.token}/maker/$route")).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                assertEquals(200, response.statusCode())
                assertContains(response.body(), "<h1>$heading</h1>")
                assertContains(response.body(), "Sandbox demo. No real money moves.")
                assertFalse(response.body().contains("th:replace"))
            }
        }
    }
}

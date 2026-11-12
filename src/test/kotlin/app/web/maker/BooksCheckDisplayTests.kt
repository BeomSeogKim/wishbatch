package app.web.maker

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals

class BooksCheckDisplayTests {
    private val display = BooksCheckDisplay(JsonMapper.builder().build())

    @Test
    fun `summarizes only recorded after comparisons and preserves unknowns`() {
        assertEquals("1 pledge · 1 matched", display.summary("""{"pledges":[{"after":{"match":"MATCH"}}]}"""))
        assertEquals("3 pledges · 1 matched, 1 mismatched, 1 unknown", display.summary(
            """{"pledges":[{"after":{"match":"MATCH"}},{"after":{"match":"MISMATCH"}},{"after":{}}]}"""))
        assertEquals("0 pledges · No comparisons", display.summary("""{"pledges":[]}"""))
        assertEquals("Books check details unavailable", display.summary("{}"))
        assertEquals("Books check details unavailable", display.summary("invalid"))
        assertEquals("Books check details unavailable", display.summary(null))
    }
}

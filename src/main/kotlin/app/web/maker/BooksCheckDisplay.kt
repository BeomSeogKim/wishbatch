package app.web.maker

import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

@Component("booksCheckDisplay")
class BooksCheckDisplay(private val mapper: JsonMapper) {
    fun summary(raw: String?): String {
        return try {
            val pledges = mapper.readTree(raw ?: return "Books check details unavailable").path("pledges")
            if (!pledges.isArray) return "Books check details unavailable"
            val count = pledges.size()
            if (count == 0) return "0 pledges · No comparisons"
            val outcomes = pledges.toList().map { it.path("after").path("match").asString("") }
            val matched = outcomes.count { it == "MATCH" }
            val mismatched = outcomes.count { it == "MISMATCH" }
            val unknown = count - matched - mismatched
            val details = buildList {
                if (matched > 0) add("$matched matched")
                if (mismatched > 0) add("$mismatched mismatched")
                if (unknown > 0) add("$unknown unknown")
            }
            "$count ${if (count == 1) "pledge" else "pledges"} · ${details.joinToString(", ")}"
        } catch (_: Exception) {
            "Books check details unavailable"
        }
    }
}

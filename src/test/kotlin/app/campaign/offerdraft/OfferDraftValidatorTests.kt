package app.campaign.offerdraft

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class OfferDraftValidatorTests {
    private val mapper = JsonMapper.builder().build()
    private val validator = OfferDraftValidator(mapper)
    private val sources = listOf("Botanical postcards", "Matte paper", "Botanical artwork")

    private fun json(title: String = "Botanical postcards", description: String = "Matte paper.") =
        mapper.writeValueAsString(mapOf("title" to title, "description" to description))

    @Test
    fun `schema permits only bounded title and description strings`() {
        val schema = mapper.valueToTree<tools.jackson.databind.JsonNode>(validator.schema())
        assertFalse(schema["additionalProperties"].asBoolean())
        assertEquals(setOf("title", "description"), schema["properties"].properties().map { it.key }.toSet())
        assertEquals(listOf("title", "description"), schema["required"].toList().map { it.asString() })
        assertEquals(120, schema["properties"]["title"]["maxLength"].asInt())
        assertEquals(1000, schema["properties"]["description"]["maxLength"].asInt())
    }

    @Test
    fun `safe approved fragments preserve original copy and compose separate sentences`() {
        val result = validator.validate(json(description = "Matte paper.\nBotanical artwork!"), sources)
        assertTrue(result.available)
        assertEquals("Botanical postcards", result.title)
        assertEquals("Matte paper. Botanical artwork!", result.description)
        assertTrue(result.removals.isEmpty())
        assertNull(result.rejection)
    }

    @Test
    fun `approved stoneware cup copy is not mistaken for a currency code`() {
        listOf("Stoneware cup", "Stoneware Cup").forEach { title ->
            val result = validator.validate(json(title, "$title."), listOf("Stoneware cup"))
            assertTrue(result.available, title)
            assertEquals(title, result.title)
            assertEquals("$title.", result.description)
            assertTrue(result.removals.isEmpty())
            assertNull(result.rejection)
        }
    }

    @Test
    fun `term boundaries preserve approved product words without accepting new cup claims`() {
        listOf("Stoneware cups", "Copper glaze", "Cupboard artwork").forEach { fragment ->
            val result = validator.validate(json(fragment, "$fragment."), listOf(fragment))
            assertTrue(result.available, fragment)
            assertTrue(result.removals.isEmpty())
        }
        listOf("Stoneware cups", "Stoneware cupful", "Waterproof stoneware cup").forEach { fragment ->
            val result = validator.validate(json("Stoneware cup", "$fragment."), listOf("Stoneware cup"))
            assertFalse(result.available, fragment)
            assertEquals(listOf(OfferDraftRemoval("description", "$fragment.", "UNSUPPORTED_CLAIM")), result.removals)
        }
    }

    @Test
    fun `currency and financial terms remain blocked in approved cup copy`() {
        listOf(
            "Stoneware CUP.", "CUP.", "USD.", "usd.", "Stoneware cup currency.",
            "Stoneware cup costs cup.", "Stoneware cup costs USD.", "Stoneware cup €.",
            "Free stoneware cup.", "Refundable stoneware cup.", "Guaranteed stoneware cup.",
        ).forEach { sentence ->
            val result = validator.validate(
                json("Stoneware cup", "Stoneware cup. $sentence"), listOf("Stoneware cup", sentence),
            )
            assertTrue(result.available, sentence)
            assertEquals("Stoneware cup.", result.description, sentence)
            assertEquals(listOf(OfferDraftRemoval("description", sentence, "COMMERCIAL_TERMS")), result.removals)
        }
    }

    @Test
    fun `schema rejects missing extra non-string duplicate and trailing fields`() {
        listOf(
            "null", "[]", "{}", "{", "{\"title\":\"Botanical postcards\"}",
            "{\"title\":null,\"description\":\"Matte paper\"}",
            "{\"title\":true,\"description\":\"Matte paper\"}",
            "{\"title\":\"Botanical postcards\",\"description\":[]}",
            json().dropLast(1) + ",\"price\":100}",
            json().dropLast(1) + ",\"title\":\"Botanical postcards\"}",
            json() + " {}",
            "\"" + "x".repeat(16000) + "\"",
        ).forEach { value ->
            val result = validator.validate(value, sources)
            assertFalse(result.available, value.take(200))
            assertEquals("Invalid draft schema.", result.rejection)
        }
    }

    @Test
    fun `limits apply before sanitizing without silently truncating copy`() {
        listOf("title" to "x".repeat(121), "description" to "x".repeat(1001)).forEach { (field, text) ->
            val result = validator.validate(if (field == "title") json(title = text) else json(description = text), sources)
            assertFalse(result.available)
            assertEquals(listOf(OfferDraftRemoval(field, text, "LENGTH_LIMIT")), result.removals)
        }
        val title = "x".repeat(120)
        val description = "y".repeat(1000)
        assertTrue(validator.validate(json(title, description), listOf(title, description)).available)
    }

    @Test
    fun `all numerical and commercial sentences are removed even when present in approved sources`() {
        val unsafe = listOf(
            "A6 postcards.", "A６ postcards.", "Postcards for ٤ fans.", "Postcards for 𝟙 fans.",
            "Postcards for Ⅳ fans.", "Twenty postcards.", "A dozen postcards.", "The first postcard.",
            "Postcards cost \$ten.", "Postcards cost €.", "Postcards cost USD.", "Postcards cost won.",
            "Pay at checkout.", "PayPal authorization.", "Refund available.", "Guaranteed satisfaction.",
            "Money back.", "Free postcards.", "A postcard per pledge.", "Minimum batch applies.",
            "Limited capacity.", "Quantity available.", "Postcard packs.", "Order now.",
        )
        unsafe.forEach { sentence ->
            val result = validator.validate(json(description = "Matte paper. $sentence"), sources + sentence)
            assertTrue(result.available, sentence)
            assertEquals("Matte paper.", result.description, sentence)
            assertEquals(sentence, result.removals.single().text, sentence)
            assertEquals("description", result.removals.single().field)
        }
    }

    @Test
    fun `delivery guarantees deadlines and dates are removed`() {
        listOf(
            "Ships soon.", "Delivery included.", "Arrives tomorrow.", "Ready in June.",
            "Dispatch before Friday.", "You will receive postcards.", "Available until summer.",
            "Expected next month.", "Deadline applies.", "Fulfillment included.",
        ).forEach { sentence ->
            val result = validator.validate(json(description = "Matte paper. $sentence"), sources + sentence)
            assertTrue(result.available, sentence)
            assertEquals(listOf(OfferDraftRemoval("description", sentence, "DELIVERY_OR_DEADLINE")), result.removals)
        }
    }

    @Test
    fun `invented features and recombined facts are removed rather than inferred`() {
        val description = "Matte paper. Waterproof postcards. Matte artwork."
        val result = validator.validate(json(description = description), sources)
        assertTrue(result.available)
        assertEquals("Matte paper.", result.description)
        assertEquals(
            listOf("Waterproof postcards.", "Matte artwork."),
            result.removals.map { it.text },
        )
        assertTrue(result.removals.all { it.reason == "UNSUPPORTED_CLAIM" })
        val negatedSource = validator.validate(json(description = "Waterproof postcards."), sources + "Not waterproof postcards")
        assertFalse(negatedSource.available)
    }

    @Test
    fun `empty meaningless or entirely removed title or body makes draft unavailable`() {
        listOf(
            json(title = ""), json(title = "!!!"), json(title = "The and"), json(description = "  "),
            json(title = "Two postcards"), json(description = "Guaranteed delivery."),
        ).forEach { value ->
            val result = validator.validate(value, sources + listOf("The and", "Two postcards", "Guaranteed delivery"))
            assertFalse(result.available, value)
            assertNull(result.title)
            assertNull(result.description)
            assertEquals("No meaningful safe title and description remain.", result.rejection)
        }
    }

    @Test
    fun `case whitespace and punctuation normalization retain approved factual fragments`() {
        val result = validator.validate(json(description = "MATTE   paper!"), sources)
        assertTrue(result.available)
        assertEquals("MATTE   paper!", result.description)
        assertFalse(validator.validate(json(), emptyList()).available)
    }

    @Test
    fun `invisible formatting cannot hide a commercial term`() {
        val sentence = "Fr\u200bee postcards."
        val result = validator.validate(json(description = "Matte paper. $sentence"), sources + sentence)
        assertTrue(result.available)
        assertEquals(listOf(OfferDraftRemoval("description", sentence, "UNSUPPORTED_FORMATTING")), result.removals)
    }
}

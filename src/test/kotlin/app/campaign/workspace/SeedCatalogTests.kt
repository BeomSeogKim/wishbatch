package app.campaign.workspace

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SeedCatalogTests {
    private val catalog = SeedCatalog()
    private val definition = catalog.definition

    @Test
    fun `catalog contains the complete demo production terms`() {
        assertEquals(3, definition.defaultMaxQtyPerPledge)
        assertEquals("Mira Fenlow", definition.maker.name)
        assertEquals(setOf("Tavi", "Orin", "Pipkin"), definition.maker.characters.keys)
        assertEquals(listOf("postcard-set", "a5-print", "sticker-sheet"), definition.options.map { it.key })
        val postcards = definition.options[0]
        assertEquals("A6 postcard set, 5 designs", postcards.name)
        assertEquals("postcard-set", postcards.format)
        assertEquals(mapOf("size" to "A6", "designCount" to "5"), postcards.fixedAttributes)
        assertEquals(mapOf("finish" to listOf("matte", "glossy")), postcards.allowedVariants)
        val print = definition.options[1]
        assertEquals("A5 art print, single design", print.name)
        assertEquals("art-print", print.format)
        assertEquals(mapOf("size" to "A5", "designCount" to "1"), print.fixedAttributes)
        assertEquals(mapOf("paper" to listOf("matte", "satin")), print.allowedVariants)
        val stickers = definition.options[2]
        assertEquals("Die-cut sticker sheet", stickers.name)
        assertEquals("sticker-sheet", stickers.format)
        assertEquals(mapOf("cut" to "die-cut"), stickers.fixedAttributes)
        assertTrue(stickers.allowedVariants.isEmpty())
        assertEquals(listOf(650L, 400L, 250L), definition.options.map { it.unitCost })
        assertEquals(listOf(1600L, 900L, 600L), definition.options.map { it.listPrice })
        assertEquals(listOf(3, 5, 5), definition.options.map { it.minBatch })
        assertEquals(listOf(10, 20, 30), definition.options.map { it.maxCapacity })
        assertEquals(listOf("2 weeks", "2 weeks", "3 weeks"), definition.options.map { it.leadTimeText })
        assertTrue(definition.options.all { it.currency == "USD" && it.shipRegion == "US" })
        assertEquals((1..40).map { "w%02d".format(it) }, catalog.wishes.map { it.key })
        assertEquals(39, catalog.wishes.map { it.text }.distinct().size)
    }

    @Test
    fun `every maker has explicit list prices above cost`() {
        assertEquals(listOf(2600L, 3600L, 4400L), catalog.scenario("ceramicist").definition.options.map { it.listPrice })
        assertEquals(listOf(2400L, 3800L, 5600L), catalog.scenario("textile").definition.options.map { it.listPrice })
        val workspaceId = UUID.randomUUID()
        for (scenario in catalog.scenarios) {
            for (seed in scenario.definition.options) {
                val option = app.campaign.model.Option(workspaceId, seed.name, seed.format, seed.fixedAttributes,
                    seed.allowedVariants, seed.unitCost, seed.currency, seed.minBatch, seed.maxCapacity,
                    seed.leadTimeText, seed.shipRegion, catalog.optionId(workspaceId, seed.key))
                assertTrue(seed.listPrice > seed.unitCost)
                assertEquals(seed.listPrice, catalog.listPrice(option))
            }
        }
    }

    @Test
    fun `seed keys are stable within a workspace and isolated between workspaces`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        assertEquals(catalog.wishId(first, "w01"), catalog.wishId(first, "w01"))
        assertNotEquals(catalog.wishId(first, "w01"), catalog.wishId(first, "w02"))
        assertNotEquals(catalog.wishId(first, "w01"), catalog.wishId(second, "w01"))
        assertEquals(catalog.optionId(first, "postcard-set"), catalog.optionId(first, "postcard-set"))
        assertNotEquals(catalog.optionId(first, "postcard-set"), catalog.optionId(second, "postcard-set"))
        assertFailsWith<IllegalArgumentException> { catalog.wishId(first, "w99") }
        assertFailsWith<IllegalArgumentException> { catalog.optionId(first, "poster") }
    }

    @Test
    fun `validation rejects invalid catalog constraints`() {
        val original = definition.options.first()
        val invalidOptions = listOf(
            original.copy(key = " "),
            original.copy(name = " "),
            original.copy(format = ""),
            original.copy(unitCost = 0),
            original.copy(unitCost = -1),
            original.copy(listPrice = original.unitCost),
            original.copy(listPrice = original.unitCost - 1),
            original.copy(currency = "usd"),
            original.copy(minBatch = 0),
            original.copy(maxCapacity = 2),
            original.copy(leadTimeText = ""),
            original.copy(shipRegion = ""),
            original.copy(fixedAttributes = mapOf("size" to "")),
            original.copy(allowedVariants = mapOf("finish" to emptyList())),
            original.copy(allowedVariants = mapOf("finish" to listOf("matte", "matte"))),
            original.copy(allowedVariants = mapOf("size" to listOf("A5"))),
        )
        for (option in invalidOptions) {
            assertFailsWith<IllegalArgumentException> {
                SeedCatalog.validate(definition.copy(options = listOf(option) + definition.options.drop(1)), catalog.wishes)
            }
        }
        for (invalid in listOf(
            definition.copy(defaultMaxQtyPerPledge = 0),
            definition.copy(options = definition.options.drop(1)),
            definition.copy(maker = definition.maker.copy(name = "")),
            definition.copy(maker = definition.maker.copy(characters = emptyMap())),
        )) {
            assertFailsWith<IllegalArgumentException> { SeedCatalog.validate(invalid, catalog.wishes) }
        }
    }

    @Test
    fun `validation rejects missing duplicate or malformed wishes`() {
        val original = catalog.wishes.first()
        for (wish in listOf(
            original.copy(key = "w02"),
            original.copy(text = " "),
            original.copy(text = "x".repeat(501)),
            original.copy(text = " Untrimmed"),
            original.copy(displayName = ""),
            original.copy(displayName = "x".repeat(256)),
        )) {
            assertFailsWith<IllegalArgumentException> {
                SeedCatalog.validate(definition, listOf(wish) + catalog.wishes.drop(1))
            }
        }
        assertFailsWith<IllegalArgumentException> { SeedCatalog.validate(definition, catalog.wishes.drop(1)) }
    }
}

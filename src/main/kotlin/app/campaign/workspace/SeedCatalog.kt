package app.campaign.workspace

import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

data class SeedMaker(val name: String, val description: String, val characters: Map<String, String>)

data class SeedOption(
    val key: String,
    val name: String,
    val format: String,
    val fixedAttributes: Map<String, String>,
    val allowedVariants: Map<String, List<String>>,
    val unitCost: Long,
    val currency: String,
    val minBatch: Int,
    val maxCapacity: Int,
    val leadTimeText: String,
    val shipRegion: String,
)

data class SeedOptions(val maker: SeedMaker, val defaultMaxQtyPerPledge: Int, val options: List<SeedOption>)

data class SeedWish(val key: String, val displayName: String, val text: String)

/** Synthetic catalog, validated before any workspace can be created. Costs are demo-scale. */
@Component
class SeedCatalog {
    private val mapper = jacksonObjectMapper()
    val definition: SeedOptions = ClassPathResource("seed/options.json").inputStream.use {
        mapper.readValue(it, SeedOptions::class.java)
    }
    val wishes: List<SeedWish> = ClassPathResource("seed/wishes.json").inputStream.use {
        mapper.readValue(it, object : TypeReference<List<SeedWish>>() {})
    }

    init {
        validate(definition, wishes)
    }

    /** Stable keys resolve within one workspace; they never reuse an entity across workspaces. */
    fun optionId(workspaceId: UUID, key: String): UUID {
        require(definition.options.any { it.key == key }) { "Unknown seed option key" }
        return seedId(workspaceId, "option", key)
    }

    fun wishId(workspaceId: UUID, key: String): UUID {
        require(wishes.any { it.key == key }) { "Unknown seed wish key" }
        return seedId(workspaceId, "wish", key)
    }

    private fun seedId(workspaceId: UUID, kind: String, key: String): UUID =
        UUID.nameUUIDFromBytes("$workspaceId:$kind:$key".toByteArray(UTF_8))

    companion object {
        fun validate(definition: SeedOptions, wishes: List<SeedWish>) {
            require(definition.maker.name.isNotBlank() && definition.maker.description.isNotBlank()) {
                "Seed maker must have a name and description"
            }
            require(definition.maker.characters.isNotEmpty() &&
                definition.maker.characters.all { (name, description) -> name.isNotBlank() && description.isNotBlank() }) {
                "Seed characters must have names and descriptions"
            }
            require(definition.defaultMaxQtyPerPledge == 3) { "Demo pledge limit must be three" }
            val options = definition.options
            require(options.size == 3 && options.map { it.key }.toSet() ==
                setOf("postcard-set", "a5-print", "sticker-sheet")) { "Invalid seed option keys" }
            for (option in options) {
                require(listOf(option.name, option.format, option.leadTimeText, option.shipRegion).all { it.isNotBlank() }) {
                    "Seed option fields must not be blank"
                }
                require(option.unitCost > 0 && option.currency.matches(Regex("[A-Z]{3}"))) {
                    "Invalid seed cost or currency"
                }
                require(option.minBatch > 0 && option.maxCapacity >= option.minBatch &&
                    definition.defaultMaxQtyPerPledge <= option.maxCapacity) { "Invalid seed production limits" }
                require(option.fixedAttributes.all { (key, value) -> key.isNotBlank() && value.isNotBlank() }) {
                    "Invalid seed fixed attributes"
                }
                require(option.allowedVariants.all { (key, values) ->
                    key.isNotBlank() && values.isNotEmpty() && values.all { it.isNotBlank() } &&
                        values.distinct().size == values.size && key !in option.fixedAttributes
                }) { "Invalid seed variants" }
            }
            require(wishes.map { it.key } == (1..40).map { "w%02d".format(it) }) { "Invalid seed wish keys" }
            require(wishes.all { it.displayName.isNotBlank() && it.displayName.length <= 255 &&
                it.text.isNotBlank() && it.text.length <= 500 && it.text == it.text.trim() }) {
                "Invalid seed wish text or display name"
            }
        }
    }
}

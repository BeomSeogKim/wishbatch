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
    val listPrice: Long,
    val currency: String,
    val minBatch: Int,
    val maxCapacity: Int,
    val leadTimeText: String,
    val shipRegion: String,
)

data class SeedOptions(val maker: SeedMaker, val defaultMaxQtyPerPledge: Int, val options: List<SeedOption>)

data class SeedWish(val key: String, val displayName: String, val text: String)

data class SeedScenario(val key: String, val definition: SeedOptions, val wishes: List<SeedWish>)

/** Synthetic catalog, validated before any workspace can be created. Costs are demo-scale. */
@Component
class SeedCatalog {
    private val mapper = jacksonObjectMapper()
    val scenarios: List<SeedScenario> = listOf("illustrator", "ceramicist", "textile").map { key ->
        val path = if (key == "illustrator") "seed" else "seed/$key"
        val definition = ClassPathResource("$path/options.json").inputStream.use {
            mapper.readValue(it, SeedOptions::class.java)
        }
        val wishes = ClassPathResource("$path/wishes.json").inputStream.use {
            mapper.readValue(it, object : TypeReference<List<SeedWish>>() {})
        }
        validate(definition, wishes)
        SeedScenario(key, definition, wishes)
    }
    val definition: SeedOptions get() = scenarios.first().definition
    val wishes: List<SeedWish> get() = scenarios.first().wishes

    fun scenario(key: String): SeedScenario = scenarios.singleOrNull { it.key == key }
        ?: throw IllegalArgumentException("Unknown scenario")

    fun forOptions(workspaceId: UUID, options: List<app.campaign.model.Option>): SeedScenario =
        scenarios.singleOrNull { scenario ->
            options.map { it.id }.toSet().containsAll(
                scenario.definition.options.map { seedId(workspaceId, "option", it.key) },
            )
        } ?: scenarios.first()

    /** Stable keys resolve within one workspace; they never reuse an entity across workspaces. */
    fun optionId(workspaceId: UUID, key: String): UUID {
        require(scenarios.any { scenario -> scenario.definition.options.any { it.key == key } }) { "Unknown seed option key" }
        return seedId(workspaceId, "option", key)
    }

    fun wishId(workspaceId: UUID, key: String): UUID {
        require(scenarios.any { scenario -> scenario.wishes.any { it.key == key } }) { "Unknown seed wish key" }
        return seedId(workspaceId, "wish", key)
    }

    fun listPrice(option: app.campaign.model.Option): Long? = scenarios
        .flatMap { it.definition.options }
        .singleOrNull { seedId(option.workspaceId, "option", it.key) == option.id }
        ?.listPrice

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
            require(options.size == 3 && options.all { it.key.isNotBlank() } && options.map { it.key }.distinct().size == 3) { "Invalid seed option keys" }
            for (option in options) {
                require(listOf(option.name, option.format, option.leadTimeText, option.shipRegion).all { it.isNotBlank() }) {
                    "Seed option fields must not be blank"
                }
                require(option.unitCost > 0 && option.currency.matches(Regex("[A-Z]{3}"))) {
                    "Invalid seed cost or currency"
                }
                require(option.listPrice > option.unitCost) { "Seed list price must exceed cost" }
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

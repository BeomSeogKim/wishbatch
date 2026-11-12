package app.campaign.model

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import tools.jackson.core.type.TypeReference
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

private val collectionMapper = jacksonObjectMapper()

abstract class JsonCollectionConverter<T>(private val type: TypeReference<T>) : AttributeConverter<T, String> {
    override fun convertToDatabaseColumn(attribute: T?): String? =
        attribute?.let { collectionMapper.writeValueAsString(it) }

    override fun convertToEntityAttribute(dbData: String?): T? =
        dbData?.let { collectionMapper.readValue(it, type) }
}

@Converter
class StringMapConverter : JsonCollectionConverter<Map<String, String>>(
    object : TypeReference<Map<String, String>>() {},
)

@Converter
class VariantMapConverter : JsonCollectionConverter<Map<String, List<String>>>(
    object : TypeReference<Map<String, List<String>>>() {},
)

@Converter
class StringListConverter : JsonCollectionConverter<List<String>>(
    object : TypeReference<List<String>>() {},
)

@Converter
class UuidListConverter : JsonCollectionConverter<List<UUID>>(
    object : TypeReference<List<UUID>>() {},
)

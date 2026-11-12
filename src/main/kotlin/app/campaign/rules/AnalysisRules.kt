package app.campaign.rules

import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.port.AnalysisOutput
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * SHA-256 of the analysis snapshot, as lowercase hex. Includes option IDs and all product fields,
 * and wish IDs and exact text. Workspace IDs and wish source, display name, time and hidden state
 * are metadata, not analysis inputs; callers select the wishes before hashing or analyzing.
 * Canonical v1 bytes use big-endian Int lengths/counts and length-prefixed UTF-8 strings.
 * Options/wishes sort by UUID string, maps by key, and allowed values lexically; numeric product
 * fields use decimal strings. Collection order never affects the hash; text is not normalized.
 */
fun analysisInputHash(options: List<Option>, wishes: List<Wish>): String {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output ->
        fun field(value: String) {
            val encoded = value.toByteArray(Charsets.UTF_8)
            output.writeInt(encoded.size)
            output.write(encoded)
        }
        field("analysis-input-v1")
        output.writeInt(options.size)
        options.sortedBy { it.id.toString() }.forEach { option ->
            field(option.id.toString())
            field(option.name)
            field(option.format)
            output.writeInt(option.fixedAttributes.size)
            option.fixedAttributes.toSortedMap().forEach { (key, value) ->
                field(key)
                field(value)
            }
            output.writeInt(option.allowedVariants.size)
            option.allowedVariants.toSortedMap().forEach { (key, values) ->
                field(key)
                output.writeInt(values.size)
                values.sorted().forEach { field(it) }
            }
            field(option.unitCost.toString())
            field(option.currency)
            field(option.minBatch.toString())
            field(option.maxCapacity.toString())
            field(option.leadTimeText)
            field(option.shipRegion)
        }
        output.writeInt(wishes.size)
        wishes.sortedBy { it.id.toString() }.forEach { wish ->
            field(wish.id.toString())
            field(wish.text)
        }
    }
    return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

data class OptionWishCounts(
    val optionId: UUID,
    val compatible: Int,
    val depends: Int,
    val notServed: Int,
    val notARequest: Int,
)

/**
 * Derives screen counts only from validated output, never model-supplied totals or quantities.
 * Validated requests have exactly one verdict per input option. Non-requests count for every
 * option, including when no requests exist. This function does not validate model output.
 */
fun optionWishCounts(output: AnalysisOutput): List<OptionWishCounts> {
    val notARequest = output.wishes.count { it.kind == WishKind.NOT_A_REQUEST }
    val verdictsByOption = output.verdicts.groupBy { it.optionId }
    return output.optionIds.map { optionId ->
        val verdicts = verdictsByOption[optionId].orEmpty()
        OptionWishCounts(
            optionId = optionId,
            compatible = verdicts.count { it.verdict == CompatibilityVerdict.COMPATIBLE },
            depends = verdicts.count { it.verdict == CompatibilityVerdict.CONDITIONAL },
            notServed = verdicts.count { it.verdict == CompatibilityVerdict.INCOMPATIBLE },
            notARequest = notARequest,
        )
    }
}

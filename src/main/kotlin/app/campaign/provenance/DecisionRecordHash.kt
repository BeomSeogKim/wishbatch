package app.campaign.provenance

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest

/** Version 1 uses sorted object keys, ordered arrays, exact integers and UTF-8 JSON. */
class DecisionRecordHash(private val mapper: JsonMapper) {
    fun canonical(value: Any): String = encode(mapper.valueToTree(value))

    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun entry(record: PledgeDecisionRecord, position: Int, kind: String, payload: String, previousHash: String?): String =
        sha256(canonical(mapOf(
            "version" to 1, "workspaceId" to record.workspaceId, "campaignId" to record.campaignId,
            "pledgeId" to record.pledgeId, "offerId" to record.offerId, "position" to position,
            "kind" to kind, "payload" to mapper.readTree(payload), "previousHash" to previousHash,
        )))

    private fun encode(node: JsonNode): String = when {
        node.isObject -> node.propertyNames().toList().sorted().joinToString(",", "{", "}") {
            mapper.writeValueAsString(it) + ":" + encode(node.get(it))
        }
        node.isArray -> node.joinToString(",", "[", "]") { encode(it) }
        else -> mapper.writeValueAsString(node)
    }
}

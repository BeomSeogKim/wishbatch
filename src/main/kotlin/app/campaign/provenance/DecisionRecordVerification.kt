package app.campaign.provenance

import tools.jackson.databind.json.JsonMapper

data class ChainVerification(
    val intact: Boolean,
    val checkedEntries: Int,
    val firstBrokenPosition: Int? = null,
    val reason: String? = null,
)

class DecisionRecordVerification(private val mapper: JsonMapper) {
    private val hashing = DecisionRecordHash(mapper)

    fun verify(record: PledgeDecisionRecord, entries: List<DecisionRecordEntry>): ChainVerification {
        var previous: String? = null
        entries.forEachIndexed { index, entry ->
            val position = index + 1
            val reason = when {
                entry.pledgeId != record.pledgeId -> "Pledge identity differs."
                entry.position != position -> "An entry is missing or out of order."
                entry.previousHash != previous -> "The previous entry hash differs."
                !runCatching {
                    hashing.canonical(mapper.readTree(entry.payload)) == entry.payload &&
                        entry.hash == hashing.entry(record, entry.position, entry.kind, entry.payload, previous)
                }.getOrDefault(false) -> "The entry content differs from its stored hash."
                else -> null
            }
            if (reason != null) return ChainVerification(false, index, position, reason)
            previous = entry.hash
        }
        if (entries.isEmpty() || entries.size != record.entryCount) {
            return ChainVerification(false, entries.size, maxOf(1, minOf(entries.size, record.entryCount) + 1),
                "The recorded entry count differs.")
        }
        if (previous != record.lastHash) {
            return ChainVerification(false, entries.size, entries.size, "The final entry hash differs from the record head.")
        }
        return ChainVerification(true, entries.size)
    }
}

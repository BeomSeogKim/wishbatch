package app.campaign.charts

import app.campaign.model.Event
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.rules.PLEDGE_TRANSITIONS
import app.campaign.rules.PledgeTransition
import java.time.Instant
import java.util.UUID

data class CountSource(val eventId: UUID, val type: String, val transition: String?)

data class CountCheckpoint(
    val anchor: String,
    val recordedAt: Instant,
    val sources: List<CountSource>,
    val interest: Int?,
    val authorized: Long?,
    val paid: Long?,
    val gap: String?,
)

data class CountHistory(val checkpoints: List<CountCheckpoint>, val limits: List<String>)

/** Equal-time independent changes form one checkpoint; UUID order is never causal evidence. */
fun campaignCountHistory(
    campaignId: UUID,
    pledges: List<Pledge>,
    events: List<Event>,
    publication: Event? = null,
    publicationInterest: Int? = null,
): CountHistory {
    val rows = pledges.associateBy { it.id }
    val limits = linkedSetOf<String>()
    val relevant = events.filter { it.type in setOf("PLEDGE_CREATED", "PLEDGE_STATE") || it.id == publication?.id }
        .sortedWith(compareBy<Event> { it.createdAt }.thenBy { it.id })
    val conflictingIds = relevant.groupBy { it.id }.values.any { copies ->
        copies.any { it.campaignId != copies.first().campaignId || it.pledgeId != copies.first().pledgeId ||
            it.type != copies.first().type || it.detail != copies.first().detail || it.createdAt != copies.first().createdAt }
    }
    val unique = relevant.distinctBy { it.id }
    var gap: String? = when {
        conflictingIds -> "Conflicting copies of a recorded event."
        rows.size != pledges.size || pledges.any { it.campaignId != campaignId || it.qty <= 0 } -> "Invalid pledge quantity or scope."
        pledges.any { pledge -> unique.none { it.type == "PLEDGE_CREATED" && it.pledgeId == pledge.id } } ->
            "Missing pledge creation history; earlier aggregate counts are unavailable."
        else -> null
    }
    gap?.let(limits::add)
    val states = mutableMapOf<UUID, PledgeState>()
    var authorized = 0L
    var paid = 0L
    val checkpoints = unique.groupBy { it.createdAt }.entries.mapIndexed { index, (time, group) ->
        fun fail(reason: String) {
            if (gap == null) gap = reason
            limits.add(reason)
        }
        val lifecycle = group.filter { it.type != "CAMPAIGN_STATE" }
        if (lifecycle.groupBy { it.pledgeId }.values.any { samePledge ->
                samePledge.map { it.type to it.detail }.distinct().size > 1
            }) fail("Ambiguous equal-time pledge sequencing; no intermediate order is known.")
        for (event in lifecycle) {
            if (gap != null) break
            val pledge = rows[event.pledgeId]
            if (event.campaignId != campaignId || pledge == null || event.createdAt < pledge.createdAt) {
                fail("Event scope or creation time does not agree with the pledge.")
                break
            }
            val previous = states[pledge.id]
            if (event.type == "PLEDGE_CREATED") {
                if (event.detail != "CREATED" || previous != null) {
                    fail("Contradictory pledge creation history.")
                } else states[pledge.id] = PledgeState.CREATED
                continue
            }
            val edge = transition(event.detail)
            if (edge == null || edge !in PLEDGE_TRANSITIONS) {
                fail("Malformed or unsupported pledge transition.")
                break
            }
            if (previous != edge.from) {
                fail("Pledge transition disagrees with its recorded prior state.")
                break
            }
            val qty = pledge.qty.toLong()
            try {
                authorized = Math.addExact(Math.subtractExact(authorized, if (previous == PledgeState.AUTHORIZED) qty else 0),
                    if (edge.to == PledgeState.AUTHORIZED) qty else 0)
                paid = Math.addExact(Math.subtractExact(paid, if (previous == PledgeState.CAPTURED) qty else 0),
                    if (edge.to == PledgeState.CAPTURED) qty else 0)
            } catch (_: ArithmeticException) {
                fail("Recorded unit totals exceed the supported integer range.")
                break
            }
            states[pledge.id] = edge.to
        }
        CountCheckpoint("checkpoint-${index + 1}", time, group.map { event ->
            CountSource(event.id, event.type, if (event.type == "PLEDGE_STATE") transition(event.detail)?.let { "${it.from} → ${it.to}" } else null)
        }, if (publication != null && group.any { it.id == publication.id }) publicationInterest else null,
            if (gap == null && lifecycle.isNotEmpty()) authorized else null,
            if (gap == null && lifecycle.isNotEmpty()) paid else null, gap)
    }
    // An omitted transition may also affect other pledges' intermediate aggregate checkpoints.
    // Without a bounded missing interval, retain sources but suppress all payment history.
    val mismatch = gap == null && pledges.any { states[it.id] != it.state }
    if (mismatch) limits.add("History does not reach the current pledge state; the missing interval cannot be located.")
    if (unique.none { it.type in setOf("PLEDGE_CREATED", "PLEDGE_STATE") }) limits.add("No recorded payment count history is available.")
    val unavailable = gap ?: if (mismatch) limits.first() else null
    return CountHistory(if (unavailable != null) checkpoints.map { it.copy(authorized = null, paid = null, gap = unavailable) } else checkpoints,
        limits.toList())
}

private fun transition(detail: String?): PledgeTransition? {
    val parts = detail?.split(" -> ") ?: return null
    if (parts.size != 2) return null
    val from = PledgeState.entries.find { it.name == parts[0] } ?: return null
    val to = PledgeState.entries.find { it.name == parts[1] } ?: return null
    return PledgeTransition(from, to)
}

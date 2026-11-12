package app.campaign.provenance

import app.campaign.model.Event
import app.campaign.model.Pledge
import app.campaign.service.CampaignPersistence
import app.campaign.store.EventRepository
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class PledgeProvenanceCheckpoint(
    private val persistence: CampaignPersistence,
    private val records: DecisionRecordService,
    private val events: EventRepository,
    private val clock: Clock,
) {
    /** Call only after committing payment truth; a corrupt chain must not roll it back. */
    fun record(pledge: Pledge) {
        try {
            persistence.checkpoint { records.recordPledge(pledge) }
        } catch (_: RuntimeException) {
            // Catch outside the failed transaction because integrity checks mark it rollback-only.
            persistence.checkpoint {
                events.saveAndFlush(Event(pledge.campaignId, "PROVENANCE_RECORD_FAILED", pledge.id,
                    detail = "Decision record checkpoint failed.", createdAt = clock.instant()))
            }
        }
    }
}

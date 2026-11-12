package app.campaign.provenance

import app.campaign.model.Event
import app.campaign.model.Pledge
import app.campaign.service.CampaignPersistence
import app.campaign.store.EventRepository
import jakarta.persistence.PersistenceException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import java.sql.SQLException
import java.time.Clock

@Component
class PledgeProvenanceCheckpoint(
    private val persistence: CampaignPersistence,
    private val records: DecisionRecordService,
    private val events: EventRepository,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(PledgeProvenanceCheckpoint::class.java)

    /** Call only after committing payment truth; a corrupt chain must not roll it back. */
    fun record(pledge: Pledge) {
        try {
            persistence.checkpoint { records.recordPledge(pledge) }
        } catch (failure: RuntimeException) {
            logFailure(failure)
            // Catch outside the failed transaction because integrity checks mark it rollback-only.
            persistence.checkpoint {
                events.saveAndFlush(Event(pledge.campaignId, "PROVENANCE_RECORD_FAILED", pledge.id,
                    detail = "Decision record checkpoint failed.", createdAt = clock.instant()))
            }
        }
    }

    fun linkReading(pledge: Pledge) {
        try {
            persistence.checkpoint { records.linkReading(pledge.id) }
        } catch (failure: RuntimeException) {
            logFailure(failure)
        }
    }

    private fun logFailure(failure: RuntimeException) {
        val causes = generateSequence<Throwable>(failure) { it.cause }.take(8).toList()
        val sql = causes.filterIsInstance<SQLException>().firstOrNull()
        val persistenceFailure = sql != null || causes.any { it is PersistenceException || it is DataAccessException }
        val reason = if (persistenceFailure) "PERSISTENCE_FAILED" else when (failure.message) {
            "Persisted payment checkpoint differs from supplied pledge" -> "PAYMENT_SOURCE_MISMATCH"
            "Stored decision record identity changed" -> "RECORD_IDENTITY_MISMATCH"
            "Stored decision record failed integrity verification" -> "CHAIN_INTEGRITY_FAILED"
            "An immutable snapshot already exists" -> "IMMUTABLE_SOURCE_MISMATCH"
            else -> if (failure is IllegalArgumentException || failure is IllegalStateException) "INVARIANT_REJECTED" else "CHECKPOINT_FAILED"
        }
        val exception = failure.javaClass.simpleName.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,79}")) } ?: "UNKNOWN"
        val sqlState = sql?.sqlState?.takeIf { it.matches(Regex("[A-Z0-9]{5}")) } ?: "UNKNOWN"
        logger.warn("Provenance checkpoint failed exception={} reason={} sqlState={}", exception, reason, sqlState)
    }
}

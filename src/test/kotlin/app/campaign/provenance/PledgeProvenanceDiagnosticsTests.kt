package app.campaign.provenance

import app.campaign.model.Event
import app.campaign.model.Pledge
import app.campaign.service.CampaignPersistence
import app.campaign.store.EventRepository
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PledgeProvenanceDiagnosticsTests {
    @Test
    fun `warnings allowlist invariant reasons and discard arbitrary failure messages`() {
        val failures = listOf(
            IllegalArgumentException("Persisted payment checkpoint differs from supplied pledge") to "PAYMENT_SOURCE_MISMATCH",
            IllegalStateException("Stored decision record identity changed") to "RECORD_IDENTITY_MISMATCH",
            IllegalStateException("Stored decision record failed integrity verification") to "CHAIN_INTEGRITY_FAILED",
            IllegalStateException("An immutable snapshot already exists") to "IMMUTABLE_SOURCE_MISMATCH",
            IllegalArgumentException(privateMessage) to "INVARIANT_REJECTED",
            RuntimeException(privateMessage) to "CHECKPOINT_FAILED",
        )
        failures.forEach { (failure, reason) -> assertDiagnostic(failure, reason) }
    }

    @Test
    fun `persistence warnings expose only exception class category and validated SQLSTATE`() {
        assertDiagnostic(DataIntegrityViolationException(privateMessage, SQLException(privateMessage, "23505")), "PERSISTENCE_FAILED", "23505")
        assertDiagnostic(DataIntegrityViolationException(privateMessage, SQLException(privateMessage, privateMessage)), "PERSISTENCE_FAILED")
    }

    private fun assertDiagnostic(failure: RuntimeException, reason: String, sqlState: String = "UNKNOWN") {
        val logger = LoggerFactory.getLogger(PledgeProvenanceCheckpoint::class.java) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val pledge = Pledge(UUID.randomUUID(), 1, 200, "USD", paypalOrderId = "private-order", lastError = privateMessage, createdAt = now)
        var transactions = 0
        var recording = false
        var failureEvent: Event? = null
        val persistence = mock(CampaignPersistence::class.java) { invocation ->
            if (invocation.method.name == "checkpoint") {
                transactions++
                @Suppress("UNCHECKED_CAST")
                (invocation.arguments[0] as () -> Any?).invoke()
            } else Answers.RETURNS_DEFAULTS.answer(invocation)
        }
        val records = mock(DecisionRecordService::class.java) { invocation ->
            if (invocation.method.name == "recordPledge") {
                assertEquals(1, transactions)
                recording = true
                throw failure
            } else Answers.RETURNS_DEFAULTS.answer(invocation)
        }
        val events = mock(EventRepository::class.java) { invocation ->
            if (invocation.method.name == "saveAndFlush") {
                assertTrue(recording)
                assertEquals(2, transactions)
                (invocation.arguments[0] as Event).also { failureEvent = it }
            } else Answers.RETURNS_DEFAULTS.answer(invocation)
        }
        try {
            PledgeProvenanceCheckpoint(persistence, records, events, Clock.fixed(now, ZoneOffset.UTC)).record(pledge)
            assertEquals(2, transactions)
            assertEquals("PROVENANCE_RECORD_FAILED", failureEvent?.type)
            assertEquals("Decision record checkpoint failed.", failureEvent?.detail)
            assertEquals(pledge.id, failureEvent?.pledgeId)
            assertNull(failureEvent?.paypalId)
            val event = logs.list.single()
            assertEquals(Level.WARN, event.level)
            assertEquals("Provenance checkpoint failed exception=${failure.javaClass.simpleName} reason=$reason sqlState=$sqlState", event.formattedMessage)
            assertNull(event.throwableProxy)
            listOf("private-", "buyer@example.test", "jdbc:", "SELECT", "Bearer", "\n").forEach {
                assertFalse(event.formattedMessage.contains(it))
            }
        } finally {
            logger.detachAppender(logs)
            logs.stop()
        }
    }

    companion object {
        private const val privateMessage = "private-payload buyer@example.test jdbc:private SELECT private-value Bearer private-token\nprivate-newline"
    }
}

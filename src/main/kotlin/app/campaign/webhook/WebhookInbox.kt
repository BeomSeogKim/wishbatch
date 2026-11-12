package app.campaign.webhook

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class PaymentWebhook(val id: String, val type: String, val resourceType: String, val resourceId: String,
    val captureId: String? = null)

@Repository
class WebhookInbox(
    private val jdbc: JdbcTemplate,
    manager: PlatformTransactionManager,
    private val clock: Clock,
    @Value("\${PAYPAL_WEBHOOK_ID:}") private val webhookId: String,
) {
    private val coordinator = ReentrantLock(true)
    private val transaction = TransactionTemplate(manager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @PostConstruct
    fun initialize() {
        if (webhookId.isBlank()) return
        jdbc.execute("""
            create table if not exists paypal_webhook_events (
                event_id varchar(255) primary key,
                event_type varchar(100) not null,
                resource_type varchar(40) not null,
                resource_id varchar(255) not null,
                capture_id varchar(255),
                received_at timestamp with time zone not null,
                processed boolean not null default false
            )
        """.trimIndent())
    }

    fun receive(event: PaymentWebhook, process: (PaymentWebhook) -> Boolean): Boolean = coordinator.withLock {
        try {
            transaction.execute {
                jdbc.update("""insert into paypal_webhook_events
                    (event_id, event_type, resource_type, resource_id, capture_id, received_at, processed)
                    values (?, ?, ?, ?, ?, ?, false)""", event.id, event.type, event.resourceType,
                    event.resourceId, event.captureId, java.sql.Timestamp.from(clock.instant()))
            }
        } catch (_: DuplicateKeyException) {
            // The primary key arbitrates concurrent deliveries across application instances.
        }
        transaction.execute {
            val stored = jdbc.queryForMap("select * from paypal_webhook_events where event_id = ? for update", event.id)
            if (stored["processed"] == true) return@execute true
            val original = PaymentWebhook(stored["event_id"] as String, stored["event_type"] as String,
                stored["resource_type"] as String, stored["resource_id"] as String, stored["capture_id"] as String?)
            if (!process(original)) return@execute false
            jdbc.update("update paypal_webhook_events set processed = true where event_id = ?", event.id)
            true
        }!!
    }
}

package app.campaign.webhook

import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebhookInboxTests {
    private val source = DriverManagerDataSource("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
    private val jdbc = JdbcTemplate(source)
    private val manager = DataSourceTransactionManager(source)
    private val event = PaymentWebhook("WH-SYNTHETIC", "PAYMENT.CAPTURE.COMPLETED", "capture", "CAPTURE1")

    @Test
    fun `additive initialization preserves existing data and survives a second initialization`() {
        jdbc.execute("create table existing_records (id integer primary key, value_text varchar(40))")
        jdbc.update("insert into existing_records values (1, 'synthetic-record')")
        val first = inbox()
        assertTrue(first.receive(event) { true })
        val restarted = inbox()
        assertTrue(restarted.receive(event) { error("Completed events must survive restart") })
        assertEquals("synthetic-record", jdbc.queryForObject("select value_text from existing_records where id = 1", String::class.java))
        assertEquals(1, count())
    }

    @Test
    fun `concurrent application instances persist and process the event once`() {
        val instances = listOf(inbox(), inbox())
        val start = CountDownLatch(1)
        val processed = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = instances.map { instance -> pool.submit<Boolean> {
                check(start.await(5, TimeUnit.SECONDS))
                instance.receive(event) { processed.incrementAndGet(); true }
            } }
            start.countDown()
            results.forEach { assertTrue(it.get(10, TimeUnit.SECONDS)) }
            assertEquals(1, count())
            assertEquals(1, processed.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `incomplete processing retries the original stored event without inserting another row`() {
        val store = inbox()
        assertFalse(store.receive(event) { false })
        val conflictingReplay = event.copy(resourceId = "DIFFERENT")
        assertTrue(store.receive(conflictingReplay) { assertEquals(event, it); true })
        assertEquals(1, count())
    }

    @Test
    fun `processing interruption leaves the durable event available for redelivery`() {
        val store = inbox()
        assertFailsWith<IllegalStateException> { store.receive(event) { error("Synthetic interruption") } }
        assertEquals(1, count())
        assertTrue(inbox().receive(event) { true })
        assertEquals(1, count())
    }

    @Test
    fun `disabled webhooks do not change an existing database schema`() {
        WebhookInbox(jdbc, manager, Clock.systemUTC(), "").initialize()
        assertEquals(0, jdbc.queryForObject("select count(*) from information_schema.tables " +
            "where lower(table_name) = 'paypal_webhook_events'", Int::class.java))
    }

    private fun inbox() = WebhookInbox(jdbc, manager, Clock.systemUTC(), "SYNTHETICHOOK").also { it.initialize() }
    private fun count() = jdbc.queryForObject("select count(*) from paypal_webhook_events", Int::class.java)
}

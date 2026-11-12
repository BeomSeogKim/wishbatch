package app.startup

import app.Application
import app.campaign.model.AnalysisRun
import app.campaign.model.Campaign
import app.campaign.model.Event
import app.campaign.model.Offer
import app.campaign.model.Option
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.model.Wish
import app.campaign.model.WishSource
import app.campaign.model.Workspace
import jakarta.persistence.EntityManagerFactory
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExistingSchemaUpgradeTests {
    @Test
    fun `actual application upgrades old native enum before JPA and survives restart`() {
        verifyApplicationUpgrade("jdbc:h2:mem:upgrade-${UUID.randomUUID()};DB_CLOSE_DELAY=-1", nativeEnum = true)
    }

    @Test
    fun `actual application widens only old enum check and survives restart`() {
        verifyApplicationUpgrade("jdbc:h2:mem:upgrade-${UUID.randomUUID()};DB_CLOSE_DELAY=-1", nativeEnum = false)
    }

    @Test
    fun `unexpected enum or business constraint fails startup without removing constraints or rows`() {
        for (constraint in listOf("state <> 'ABANDONED'", "state in ('CAPTURED','UNKNOWN')")) {
            val url = "jdbc:h2:mem:upgrade-${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
            DriverManager.getConnection(url, "sa", "").use { connection ->
                connection.execute("create table pledges (state varchar(255) not null, qty integer not null)")
                connection.execute("alter table pledges add constraint custom_state check ($constraint)")
                connection.execute("insert into pledges values ('CAPTURED',2)")
                val failure = assertFailsWith<Exception> { startApplication(url).close() }
                assertTrue(generateSequence<Throwable>(failure) { it.cause }.any {
                    it.message?.contains("Unsupported schema: pledges.state") == true
                })
                assertEquals("CAPTURED", connection.scalar("select state from pledges"))
                assertFailsWith<SQLException> { connection.execute("update pledges set state='ABANDONED'") }
            }
        }
    }

    @Test
    fun `nullable duplicate counts backfill zero while preserving known duplicates and original text`() {
        val url = "jdbc:h2:mem:upgrade-${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.execute("create table wishes (text varchar(500), duplicate_count integer)")
            connection.execute("insert into wishes values ('Original text',null),('Repeated text',3)")
            val upgrade = ExistingSchemaUpgrade(DriverManagerDataSource(url, "sa", ""))
            repeat(2) { upgrade.upgrade() }
            assertEquals(0, connection.scalar("select duplicate_count from wishes where text='Original text'"))
            assertEquals(3, connection.scalar("select duplicate_count from wishes where text='Repeated text'"))
            assertFailsWith<SQLException> { connection.execute("insert into wishes values ('Invalid',null)") }
            connection.execute("insert into wishes(text) values ('Defaulted')")
            assertEquals(0, connection.scalar("select duplicate_count from wishes where text='Defaulted'"))
        }
    }

    @Test
    fun `unexpected column type and enum labels fail without losing data`() {
        for (ddl in listOf(
            "create table wishes (duplicate_count varchar(30))",
            "create table pledges (state enum('CAPTURED','UNKNOWN'))",
        )) {
            val url = "jdbc:h2:mem:upgrade-${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
            DriverManager.getConnection(url, "sa", "").use { connection ->
                connection.execute(ddl)
                val failure = assertFailsWith<IllegalStateException> {
                    ExistingSchemaUpgrade(DriverManagerDataSource(url, "sa", "")).upgrade()
                }
                assertTrue(failure.message!!.startsWith("Unsupported schema:"))
            }
        }
    }

    internal fun verifyApplicationUpgrade(url: String, nativeEnum: Boolean, postgres: Boolean = false) {
        DriverManager.getConnection(url, if (postgres) "postgres" else "sa", "").use { connection ->
            val pledge = Pledge(UUID.randomUUID(), 2, 400, "USD", PledgeState.CAPTURED,
                createdAt = Instant.parse("2026-01-01T00:00:00Z"))
            val wish = Wish(UUID.randomUUID(), "Original imported text", WishSource.IMPORTED,
                createdAt = pledge.createdAt)
            createLegacySchema(url, pledge, wish, nativeEnum, postgres)
            assertFailsWith<SQLException> { connection.execute("update pledges set state='PARTIALLY_REFUNDED'") }
            repeat(2) {
                val observedBeforeJpa = AtomicBoolean()
                startApplication(url, postgres, observedBeforeJpa).use { context ->
                    assertTrue(observedBeforeJpa.get(), "Upgrade must finish before EntityManagerFactory initialization")
                    assertTrue("schemaUpgrade" in context.beanFactory.getBeanDefinition("entityManagerFactory").dependsOn!!)
                    assertEquals(if (it == 0) "CAPTURED" else "PARTIALLY_REFUNDED", connection.scalar("select state from pledges").toString())
                    val factory = context.getBean(EntityManagerFactory::class.java)
                    factory.createEntityManager().use { entityManager ->
                        entityManager.transaction.begin()
                        val saved = assertNotNull(entityManager.find(Pledge::class.java, pledge.id))
                        assertEquals(if (it == 0) PledgeState.CAPTURED else PledgeState.PARTIALLY_REFUNDED, saved.state)
                        assertEquals(2, saved.qty)
                        assertEquals(400L, saved.amount)
                        saved.state = PledgeState.PARTIALLY_REFUNDED
                        saved.lastError = "Synthetic migration check"
                        entityManager.transaction.commit()
                        entityManager.clear()
                        val reloaded = assertNotNull(entityManager.find(Pledge::class.java, pledge.id))
                        assertEquals(PledgeState.PARTIALLY_REFUNDED, reloaded.state)
                        assertEquals("Synthetic migration check", reloaded.lastError)
                        assertEquals(2, reloaded.qty)
                        assertEquals(400L, reloaded.amount)
                        val savedWish = assertNotNull(entityManager.find(Wish::class.java, wish.id))
                        assertEquals("Original imported text", savedWish.text)
                        assertEquals(0, savedWish.duplicateCount)
                    }
                    assertEquals("Original imported text", connection.scalar("select text from wishes"))
                    assertEquals(0, connection.scalar("select duplicate_count from wishes"))
                    assertEquals(2, connection.scalar("select qty from pledges"))
                    assertEquals(400L, connection.scalar("select amount from pledges"))
                    assertEquals(0L, connection.scalar("select count(*) from decision_source_snapshots"))
                    assertEquals(0L, connection.scalar("select count(*) from pledge_decision_records"))
                    assertEquals(0L, connection.scalar("select count(*) from decision_record_entries"))
                    assertEquals(0L, connection.scalar("select count(*) from wish_review_overrides"))
                    assertFailsWith<SQLException> { connection.execute("update pledges set qty=0") }
                }
                assertEquals("PARTIALLY_REFUNDED", connection.scalar("select state from pledges").toString())
            }
        }
    }

    private fun createLegacySchema(url: String, pledge: Pledge, wish: Wish, nativeEnum: Boolean, postgres: Boolean) {
        val registry = StandardServiceRegistryBuilder().applySettings(mapOf(
            "hibernate.connection.url" to url,
            "hibernate.connection.username" to if (postgres) "postgres" else "sa",
            "hibernate.connection.password" to "",
            "hibernate.hbm2ddl.auto" to "create",
            "hibernate.hbm2ddl.halt_on_error" to true,
            "hibernate.physical_naming_strategy" to "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl",
        )).build()
        try {
            val sources = MetadataSources(registry)
            listOf(Workspace::class.java, Option::class.java, Wish::class.java, AnalysisRun::class.java,
                Offer::class.java, Campaign::class.java, Pledge::class.java, Event::class.java).forEach(sources::addAnnotatedClass)
            sources.buildMetadata().buildSessionFactory().use { factory ->
                factory.inTransaction { session -> session.persist(pledge); session.persist(wish) }
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry)
        }
        DriverManager.getConnection(url, if (postgres) "postgres" else "sa", "").use { connection ->
            val labels = "'ABANDONED','AUTHORIZED','CAPTURED','CAPTURE_FAILED','CAPTURE_PENDING','CREATED'," +
                "'REFUNDED','REFUND_FAILED','REFUND_PENDING','VOIDED','VOID_FAILED'"
            if (postgres) {
                connection.execute("alter table pledges drop constraint if exists pledges_state_check")
                if (nativeEnum) {
                    connection.execute("create type legacy_pledge_state as enum ($labels)")
                    connection.execute("alter table pledges alter column state type legacy_pledge_state using state::text::legacy_pledge_state")
                }
            } else {
                connection.execute("alter table pledges alter column state ${if (nativeEnum) "enum($labels)" else "varchar(255)"}")
            }
            if (!nativeEnum) connection.execute("alter table pledges add constraint old_pledge_state check (state in ($labels))")
            connection.execute("alter table pledges add constraint positive_qty check (qty > 0)")
            connection.execute("alter table wishes drop column if exists duplicate_count")
        }
    }

    private fun startApplication(url: String, postgres: Boolean = false, observed: AtomicBoolean? = null): ConfigurableApplicationContext {
        val builder = SpringApplicationBuilder(Application::class.java).web(WebApplicationType.NONE)
        if (observed != null) builder.initializers({ context ->
            context.addBeanFactoryPostProcessor { factory ->
                factory.addBeanPostProcessor(object : BeanPostProcessor {
                    override fun postProcessBeforeInitialization(bean: Any, beanName: String): Any {
                        if (bean is LocalContainerEntityManagerFactoryBean) {
                            DriverManager.getConnection(url, if (postgres) "postgres" else "sa", "").use { connection ->
                                val previous = connection.scalar("select state from pledges").toString()
                                connection.execute("update pledges set state='PARTIALLY_REFUNDED'")
                                assertEquals(0, connection.scalar("select duplicate_count from wishes"))
                                connection.execute("update pledges set state='$previous'")
                            }
                            observed.set(true)
                        }
                        return bean
                    }
                })
            }
        })
        return builder.run("--spring.datasource.url=$url", "--spring.datasource.username=${if (postgres) "postgres" else "sa"}",
            "--spring.datasource.password=", "--PAYPAL_CLIENT_ID=", "--PAYPAL_CLIENT_SECRET=", "--PAYPAL_WEBHOOK_ID=",
            "--LLM_API_KEY=", "--LLM_SEED_RUN=false", "--spring.main.banner-mode=off", "--logging.level.root=ERROR")
    }

    private fun Connection.execute(sql: String) { createStatement().use { it.execute(sql) } }
    private fun Connection.scalar(sql: String): Any? = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> check(rows.next()); rows.getObject(1) }
    }
}

package app.campaign

import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import org.hibernate.SessionFactory
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class PledgeStateSchemaCompatibilityTests {
    @Test
    fun `schema update retains existing pledge data but enum constraints require expansion`() {
        val allStates = PledgeState.entries.joinToString(",") { "'${it.name}'" }
        val legacyStates = PledgeState.entries.filter { it != PledgeState.PARTIALLY_REFUNDED }
            .joinToString(",") { "'${it.name}'" }
        for (nativeEnum in listOf(true, false)) {
            val url = "jdbc:h2:mem:pledge-schema-${UUID.randomUUID()}"
            DriverManager.getConnection(url, "sa", "").use { connection ->
                val pledge = Pledge(UUID.randomUUID(), 2, 400, "USD", PledgeState.CAPTURED,
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"))
                withFactory(url, "create") { factory ->
                    factory.inTransaction { it.persist(pledge) }
                }
                connection.execute(if (nativeEnum) {
                    "alter table pledges alter column state enum($legacyStates)"
                } else {
                    "alter table pledges alter column state varchar(255)"
                })
                if (!nativeEnum) connection.execute(
                    "alter table pledges add constraint legacy_pledge_state check (state in ($legacyStates))",
                )

                withFactory(url, "update") { factory ->
                    factory.inSession { session ->
                        val saved = assertNotNull(session.find(Pledge::class.java, pledge.id))
                        assertEquals(PledgeState.CAPTURED, saved.state)
                        assertEquals(400L, saved.amount)
                        assertEquals(2, saved.qty)
                    }
                    assertFailsWith<SQLException> {
                        connection.execute("update pledges set state = 'PARTIALLY_REFUNDED' where id = '${pledge.id}'")
                    }
                    if (nativeEnum) {
                        connection.execute("alter table pledges alter column state enum($allStates)")
                    } else {
                        connection.execute("alter table pledges add constraint expanded_pledge_state check (state in ($allStates))")
                        connection.execute("alter table pledges drop constraint legacy_pledge_state")
                    }
                    factory.inTransaction { session ->
                        val saved = assertNotNull(session.find(Pledge::class.java, pledge.id))
                        assertEquals(PledgeState.CAPTURED, saved.state)
                        saved.state = PledgeState.PARTIALLY_REFUNDED
                    }
                    factory.inSession { session ->
                        val saved = assertNotNull(session.find(Pledge::class.java, pledge.id))
                        assertEquals(PledgeState.PARTIALLY_REFUNDED, saved.state)
                        assertEquals(400L, saved.amount)
                        assertEquals(2, saved.qty)
                    }
                }
            }
        }
    }

    private fun withFactory(url: String, schemaAction: String, action: (SessionFactory) -> Unit) {
        val registry = StandardServiceRegistryBuilder().applySettings(mapOf(
            "hibernate.connection.url" to url,
            "hibernate.connection.driver_class" to "org.h2.Driver",
            "hibernate.connection.username" to "sa",
            "hibernate.connection.password" to "",
            "hibernate.hbm2ddl.auto" to schemaAction,
            "hibernate.hbm2ddl.halt_on_error" to true,
        )).build()
        try {
            MetadataSources(registry).addAnnotatedClass(Pledge::class.java).buildMetadata()
                .buildSessionFactory().use(action)
        } finally {
            StandardServiceRegistryBuilder.destroy(registry)
        }
    }

    private fun Connection.execute(sql: String) {
        createStatement().use { it.execute(sql) }
    }
}

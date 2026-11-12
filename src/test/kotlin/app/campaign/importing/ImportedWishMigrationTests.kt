package app.campaign.importing

import app.campaign.model.Wish
import app.campaign.model.WishSource
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImportedWishMigrationTests {
    @Test
    fun `schema update preserves existing wishes and defaults duplicate occurrences to zero`() {
        val databaseUrl = "jdbc:h2:mem:import-migration-${UUID.randomUUID()}"
        val wishId = UUID.randomUUID()
        val workspaceId = UUID.randomUUID()
        val createdAt = Instant.parse("2026-01-02T03:04:05.123456Z")
        val text = "  A matte A5 print, please!  "

        DriverManager.getConnection(databaseUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("""
                    create table wishes (
                        id uuid primary key,
                        workspace_id uuid not null,
                        text varchar(500) not null,
                        source enum ('IMPORTED', 'ONSITE') not null,
                        display_name varchar(255),
                        created_at timestamp(6) with time zone not null,
                        hidden boolean not null
                    )
                """.trimIndent())
            }
            connection.prepareStatement(
                "insert into wishes (id, workspace_id, text, source, display_name, created_at, hidden) " +
                    "values (?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, wishId)
                statement.setObject(2, workspaceId)
                statement.setString(3, text)
                statement.setString(4, WishSource.IMPORTED.name)
                statement.setString(5, "Sample fan")
                statement.setObject(6, createdAt.atOffset(ZoneOffset.UTC))
                statement.setBoolean(7, true)
                assertEquals(1, statement.executeUpdate())
            }

            val registry = StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", databaseUrl)
                .applySetting("hibernate.connection.username", "sa")
                .applySetting("hibernate.connection.password", "")
                .applySetting("hibernate.hbm2ddl.auto", "update")
                .applySetting("hibernate.hbm2ddl.halt_on_error", true)
                .applySetting(
                    "hibernate.physical_naming_strategy",
                    PhysicalNamingStrategySnakeCaseImpl::class.java.name,
                )
                .build()
            try {
                MetadataSources(registry).addAnnotatedClass(Wish::class.java)
                    .buildMetadata().buildSessionFactory().use { sessionFactory ->
                        sessionFactory.openSession().use { session ->
                            val transaction = session.beginTransaction()
                            val wish = assertNotNull(session.find(Wish::class.java, wishId))
                            assertEquals(workspaceId, wish.workspaceId)
                            assertEquals(text, wish.text)
                            assertEquals(WishSource.IMPORTED, wish.source)
                            assertEquals("Sample fan", wish.displayName)
                            assertEquals(createdAt, wish.createdAt)
                            assertTrue(wish.hidden)
                            assertEquals(0, wish.duplicateCount)
                            wish.duplicateCount = 2
                            transaction.commit()
                        }
                        sessionFactory.openSession().use { session ->
                            val wish = assertNotNull(session.find(Wish::class.java, wishId))
                            assertEquals(2, wish.duplicateCount)
                            assertEquals(text, wish.text)
                        }
                    }
            } finally {
                StandardServiceRegistryBuilder.destroy(registry)
            }
        }
    }
}

package app.campaign.review

import app.campaign.model.AnalysisRun
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Workspace
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WishReviewSchemaTests {
    @Test
    fun `schema update adds review storage without replacing existing analysis records`() {
        val dataSource = DriverManagerDataSource(
            "jdbc:h2:mem:review-upgrade-${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "",
        )
        val createdAt = Instant.parse("2026-01-02T03:04:05Z")
        val workspace = Workspace("synthetic-upgrade-workspace", createdAt)
        val oldJson = """{"wishes":[],"verdicts":[],"proposal":null,"noProposalReason":"No requests.","notServed":[],"questionsForMaker":[],"alternatives":[],"optionIds":[]}"""
        val run = AnalysisRun(workspace.id, "stored-model", "stored-input-hash", oldJson, createdAt = createdAt)

        try {
            val baseline = factory(dataSource, "create", includeReviews = false)
            try {
                val entityManager = baseline.`object`!!.createEntityManager()
                try {
                    entityManager.transaction.begin()
                    entityManager.persist(workspace)
                    entityManager.persist(run)
                    entityManager.transaction.commit()
                } finally {
                    entityManager.close()
                }
                assertEquals(0, reviewTableCount(dataSource))
            } finally {
                baseline.destroy()
            }

            val upgraded = factory(dataSource, "update", includeReviews = true)
            try {
                assertEquals(1, reviewTableCount(dataSource))
                val decision = WishReviewOverride(
                    workspace.id, run.id, UUID.randomUUID(), UUID.randomUUID(),
                    CompatibilityVerdict.COMPATIBLE, CompatibilityVerdict.CONDITIONAL,
                    "The catalog satisfies this request.", createdAt.plusSeconds(60), 1,
                )
                val entityManager = upgraded.`object`!!.createEntityManager()
                try {
                    val existing = assertNotNull(entityManager.find(AnalysisRun::class.java, run.id))
                    assertEquals(oldJson, existing.resultJson)
                    assertEquals("stored-model", existing.model)
                    assertEquals(workspace.token, entityManager.find(Workspace::class.java, workspace.id).token)
                    entityManager.transaction.begin()
                    entityManager.persist(decision)
                    entityManager.transaction.commit()
                    entityManager.clear()

                    val storedDecision = assertNotNull(entityManager.find(WishReviewOverride::class.java, decision.id))
                    assertEquals(run.id, storedDecision.runId)
                    assertEquals(decision.wishId, storedDecision.wishId)
                    assertEquals(decision.optionId, storedDecision.optionId)
                    assertEquals(CompatibilityVerdict.CONDITIONAL, storedDecision.previousVerdict)
                    assertEquals(CompatibilityVerdict.COMPATIBLE, storedDecision.verdict)
                    assertEquals("MAKER", storedDecision.source)
                    assertEquals(decision.reason, storedDecision.reason)
                    assertEquals(decision.createdAt, storedDecision.createdAt)
                    assertEquals(1L, storedDecision.revision)
                    assertEquals(oldJson, entityManager.find(AnalysisRun::class.java, run.id).resultJson)
                } finally {
                    entityManager.close()
                }
            } finally {
                upgraded.destroy()
            }
        } finally {
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("SHUTDOWN") }
            }
        }
    }

    private fun factory(
        dataSource: DriverManagerDataSource,
        schemaAction: String,
        includeReviews: Boolean,
    ): LocalContainerEntityManagerFactoryBean = LocalContainerEntityManagerFactoryBean().apply {
        setDataSource(dataSource)
        jpaVendorAdapter = HibernateJpaVendorAdapter()
        val packages = mutableListOf("app.campaign.model", "app.campaign.analysisrun")
        if (includeReviews) packages.add("app.campaign.review")
        setPackagesToScan(*packages.toTypedArray())
        setJpaPropertyMap(mapOf(
            "hibernate.hbm2ddl.auto" to schemaAction,
            "hibernate.hbm2ddl.halt_on_error" to true,
            "hibernate.physical_naming_strategy" to "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
        ))
        afterPropertiesSet()
    }

    private fun reviewTableCount(dataSource: DriverManagerDataSource): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("select count(*) from information_schema.tables where table_name = 'WISH_REVIEW_OVERRIDES'").use {
                it.next()
                it.getInt(1)
            }
        }
    }
}

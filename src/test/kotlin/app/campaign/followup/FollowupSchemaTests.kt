package app.campaign.followup

import app.campaign.model.AnalysisRun
import app.campaign.model.CompatibilityVerdict
import app.campaign.model.NeedAttribute
import app.campaign.model.Workspace
import app.campaign.review.WishReviewOverride
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FollowupSchemaTests {
    @Test
    fun `schema update preserves legacy maker reviews and adds nullable answer provenance and followup storage`() {
        val dataSource = DriverManagerDataSource(
            "jdbc:h2:mem:followup-upgrade-${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "",
        )
        val createdAt = Instant.parse("2026-01-02T03:04:05Z")
        val workspace = Workspace("synthetic-followup-upgrade", createdAt)
        val originalJson = """{"wishes":[],"verdicts":[],"proposal":null,"noProposalReason":"No requests.","notServed":[],"questionsForMaker":[],"alternatives":[],"optionIds":[]}"""
        val run = AnalysisRun(workspace.id, "stored-model", "stored-hash", originalJson, createdAt = createdAt)
        val legacy = WishReviewOverride(
            workspace.id, run.id, UUID.randomUUID(), UUID.randomUUID(),
            CompatibilityVerdict.COMPATIBLE, CompatibilityVerdict.CONDITIONAL,
            "The catalog satisfies the request.", createdAt.plusSeconds(30), 7,
        )

        try {
            val baseline = factory(dataSource, "create", includeFollowups = false)
            try {
                val entityManager = baseline.`object`!!.createEntityManager()
                try {
                    entityManager.transaction.begin()
                    entityManager.persist(workspace)
                    entityManager.persist(run)
                    entityManager.persist(legacy)
                    entityManager.transaction.commit()
                } finally {
                    entityManager.close()
                }
                assertTrue(followupTables(dataSource).isEmpty())
            } finally {
                baseline.destroy()
            }

            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("alter table wish_review_overrides drop column answer_id") }
            }
            assertFalse(answerColumnExists(dataSource))

            val upgraded = factory(dataSource, "update", includeFollowups = true)
            try {
                assertEquals(setOf("FOLLOWUP_DRAFT_ATTEMPTS", "FOLLOWUP_QUESTIONS", "FOLLOWUP_ANSWERS"), followupTables(dataSource))
                assertTrue(answerColumnExists(dataSource))
                dataSource.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery(
                            "select is_nullable, column_default from information_schema.columns " +
                                "where table_name = 'WISH_REVIEW_OVERRIDES' and column_name = 'ANSWER_ID'",
                        ).use {
                            assertTrue(it.next())
                            assertEquals("YES", it.getString(1))
                            assertNull(it.getString(2))
                        }
                        for (table in listOf("followup_draft_attempts", "followup_questions", "followup_answers")) {
                            statement.executeQuery("select count(*) from $table").use {
                                assertTrue(it.next())
                                assertEquals(0, it.getInt(1))
                            }
                        }
                    }
                }

                val entityManager = upgraded.`object`!!.createEntityManager()
                try {
                    val savedLegacy = assertNotNull(entityManager.find(WishReviewOverride::class.java, legacy.id))
                    assertEquals("MAKER", savedLegacy.source)
                    assertEquals(7L, savedLegacy.revision)
                    assertNull(savedLegacy.answerId)
                    assertEquals(legacy.reason, savedLegacy.reason)
                    assertEquals(legacy.createdAt, savedLegacy.createdAt)
                    assertEquals(originalJson, entityManager.find(AnalysisRun::class.java, run.id).resultJson)

                    val wish = UUID.randomUUID()
                    val option = UUID.randomUUID()
                    val question = FollowupQuestion(workspace.id, run.id, option, NeedAttribute.FINISH, "finish",
                        "Which finish works for you?", listOf("matte", "glossy"), listOf(wish), createdAt.plusSeconds(60))
                    assertFalse(question.approved)
                    question.approved = true
                    val answer = FollowupAnswerRecord(workspace.id, run.id, option, question.id, wish, "matte", createdAt.plusSeconds(90))
                    val attempt = FollowupDraftAttempt(run.id, workspace.id, createdAt.plusSeconds(60), model = "synthetic-model")
                    val resolution = WishReviewOverride(workspace.id, run.id, wish, option,
                        CompatibilityVerdict.COMPATIBLE, CompatibilityVerdict.CONDITIONAL,
                        "Fan confirmed the approved finish.", createdAt.plusSeconds(90), 1,
                        source = "FAN_ANSWER", answerId = answer.id)
                    entityManager.transaction.begin()
                    entityManager.persist(attempt)
                    entityManager.persist(question)
                    entityManager.persist(answer)
                    entityManager.persist(resolution)
                    entityManager.transaction.commit()
                    entityManager.clear()

                    val savedQuestion = assertNotNull(entityManager.find(FollowupQuestion::class.java, question.id))
                    assertEquals(listOf("matte", "glossy"), savedQuestion.choices)
                    assertEquals(listOf(wish), savedQuestion.wishIds)
                    assertTrue(savedQuestion.approved)
                    val savedAnswer = assertNotNull(entityManager.find(FollowupAnswerRecord::class.java, answer.id))
                    assertEquals(question.id, savedAnswer.questionId)
                    assertEquals(wish, savedAnswer.wishId)
                    assertEquals("matte", savedAnswer.choice)
                    assertEquals("synthetic-model", entityManager.find(FollowupDraftAttempt::class.java, run.id).model)
                    val savedResolution = assertNotNull(entityManager.find(WishReviewOverride::class.java, resolution.id))
                    assertEquals("FAN_ANSWER", savedResolution.source)
                    assertEquals(answer.id, savedResolution.answerId)
                    assertEquals(originalJson, entityManager.find(AnalysisRun::class.java, run.id).resultJson)
                    assertEquals(workspace.token, entityManager.find(Workspace::class.java, workspace.id).token)
                    assertNull(entityManager.find(WishReviewOverride::class.java, legacy.id).answerId)
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
        action: String,
        includeFollowups: Boolean,
    ): LocalContainerEntityManagerFactoryBean = LocalContainerEntityManagerFactoryBean().apply {
        setDataSource(dataSource)
        jpaVendorAdapter = HibernateJpaVendorAdapter()
        val packages = mutableListOf("app.campaign.model", "app.campaign.analysisrun", "app.campaign.review")
        if (includeFollowups) packages.add("app.campaign.followup")
        setPackagesToScan(*packages.toTypedArray())
        setJpaPropertyMap(mapOf(
            "hibernate.hbm2ddl.auto" to action,
            "hibernate.hbm2ddl.halt_on_error" to true,
            "hibernate.physical_naming_strategy" to "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
        ))
        afterPropertiesSet()
    }

    private fun followupTables(dataSource: DriverManagerDataSource): Set<String> = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("select table_name from information_schema.tables where table_name like 'FOLLOWUP_%'").use {
                buildSet { while (it.next()) add(it.getString(1)) }
            }
        }
    }

    private fun answerColumnExists(dataSource: DriverManagerDataSource): Boolean = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "select count(*) from information_schema.columns where table_name = 'WISH_REVIEW_OVERRIDES' and column_name = 'ANSWER_ID'",
            ).use { it.next(); it.getInt(1) == 1 }
        }
    }
}

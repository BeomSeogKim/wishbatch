package app.campaign.provenance

import app.campaign.model.AnalysisRun
import app.campaign.model.Campaign
import app.campaign.model.Event
import app.campaign.model.Offer
import app.campaign.model.Option
import app.campaign.model.Pledge
import app.campaign.model.Wish
import app.campaign.model.Workspace
import org.hibernate.SessionFactory
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DecisionRecordSchemaTests {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Test
    fun `additive schema update preserves an existing database and records survive reopening`() {
        val url = "jdbc:h2:mem:decision-schema-${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val workspace = Workspace("synthetic-schema-workspace", now)
        val run = AnalysisRun(workspace.id, "synthetic", "input-hash",
            """{"wishes":[],"verdicts":[],"proposal":null,"noProposalReason":"No wishes","notServed":[],"questionsForMaker":[],"alternatives":[],"optionIds":[]}""",
            createdAt = now)
        val offer = Offer(workspace.id, UUID.randomUUID(), run.id, "Synthetic offer", "Original specification",
            emptyMap(), 200, "USD", "Original delivery", "US", 1, 5)
        val campaign = Campaign(offer.id)
        val pledge = Pledge(campaign.id, 1, 200, "USD", createdAt = now)
        factory(url, "create", provenance = false).use { old ->
            old.openSession().use { session ->
                val transaction = session.beginTransaction()
                listOf(workspace, run, offer, campaign, pledge).forEach(session::persist)
                transaction.commit()
            }
        }
        factory(url, "update", provenance = true).use { updated ->
            updated.openSession().use { session ->
                val transaction = session.beginTransaction()
                assertEquals("Original specification", session.find(Offer::class.java, offer.id).specText)
                val records = DecisionRecordService(session, mapper)
                records.recordAnalysis(session.find(AnalysisRun::class.java, run.id), emptyList())
                records.recordOffer(session.find(Offer::class.java, offer.id))
                records.recordPledge(session.find(Pledge::class.java, pledge.id))
                transaction.commit()
            }
        }
        factory(url, "validate", provenance = true).use { reopened ->
            reopened.openSession().use { session ->
                val transaction = session.beginTransaction()
                val saved = assertNotNull(session.find(PledgeDecisionRecord::class.java, pledge.id))
                val records = DecisionRecordService(session, mapper)
                assertEquals(4, saved.entryCount)
                assertTrue(records.chainValid(saved, records.entries(pledge.id)))
                assertTrue(records.sourcesValid(saved, records.entries(pledge.id)))
                assertEquals("Original delivery", session.find(Offer::class.java, offer.id).deliveryTerms)
                assertEquals(workspace.token, session.find(Workspace::class.java, workspace.id).token)
                transaction.commit()
            }
        }
    }

    private fun factory(url: String, ddl: String, provenance: Boolean): SessionFactory {
        val registry = StandardServiceRegistryBuilder().applySettings(mapOf(
            "hibernate.connection.driver_class" to "org.h2.Driver", "hibernate.connection.url" to url,
            "hibernate.connection.username" to "sa", "hibernate.connection.password" to "",
            "hibernate.hbm2ddl.auto" to ddl, "hibernate.show_sql" to "false",
            "hibernate.physical_naming_strategy" to "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl",
        )).build()
        val sources = MetadataSources(registry)
        listOf(Workspace::class.java, Option::class.java, Wish::class.java, AnalysisRun::class.java,
            Offer::class.java, Campaign::class.java, Pledge::class.java, Event::class.java).forEach(sources::addAnnotatedClass)
        if (provenance) listOf(DecisionSourceSnapshot::class.java, PledgeDecisionRecord::class.java,
            DecisionRecordEntry::class.java).forEach(sources::addAnnotatedClass)
        return sources.buildMetadata().buildSessionFactory()
    }
}

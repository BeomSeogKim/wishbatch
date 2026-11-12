package app.campaign.provenance

import app.Application
import app.campaign.analysisrun.AnalysisRunService
import app.campaign.model.AnalysisRun
import app.campaign.model.CampaignState
import app.campaign.model.Money
import app.campaign.model.Pledge
import app.campaign.model.PledgeState
import app.campaign.model.Workspace
import app.campaign.port.AuthorizationDetails
import app.campaign.port.AuthorizationId
import app.campaign.port.CaptureDetails
import app.campaign.port.CaptureId
import app.campaign.port.OrderDetails
import app.campaign.port.OrderId
import app.campaign.port.PaymentResult
import app.campaign.port.PaymentStatus
import app.campaign.port.RefundDetails
import app.campaign.port.RefundId
import app.campaign.port.VoidDetails
import app.campaign.service.CampaignPayments
import app.campaign.service.CampaignPersistence
import app.campaign.service.CampaignRef
import app.campaign.service.CampaignService
import app.campaign.service.CampaignServiceTests
import app.campaign.service.CrashableGateway
import app.campaign.service.DraftOffer
import app.campaign.service.MutableClock
import app.campaign.service.PledgeRef
import app.campaign.service.ServiceResult
import app.campaign.store.EventRepository
import app.campaign.store.PledgeRepository
import app.campaign.workspace.WorkspaceService
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProvenancePostgresTests {
    @Test
    fun `nanosecond payment checkpoints retain initial no-order truth and reject real drift`(@TempDir directory: Path) =
        postgres(directory) { fixture ->
            val pledge = fixture.authorize(3)
            val saved = fixture.pledges.findById(pledge.id).orElseThrow()
            assertEquals(Instant.parse("2026-01-01T00:00:00.123457Z"), saved.createdAt)
            assertEquals(Instant.parse("2026-01-02T00:00:00Z"), saved.authorizationExpiresAt)
            val before = fixture.document().pledges.single()
            val payments = before.entries.filter { it.kind == "PAYMENT" }
            assertEquals(listOf("CREATED", "CREATED", "CREATED", "AUTHORIZED"), payments.map { it.payload["state"].asString() })
            assertEquals("INTACT", before.integrity)
            assertTrue(payments.first().payload["orderId"].isNull)
            assertTrue(payments.first().payload["authorizationId"].isNull)
            assertEquals(pledge.paypalOrderId, payments[1].payload["orderId"].asString())
            assertEquals(saved.createdAt.toString(), payments.first().payload["createdAt"].asString())
            assertEquals(saved.authorizationExpiresAt.toString(), payments.last().payload["authorizationExpiresAt"].asString())
            fixture.gateway.fake.captureResults.add(PaymentResult.Received(fixture.capture(saved), PaymentStatus.COMPLETED))
            assertEquals(CampaignState.FUNDED, fixture.success(fixture.service.confirmProduction(fixture.ref)).state)
            val captured = fixture.document()
            assertEquals("INTACT", captured.pledges.single().integrity)
            assertEquals("CAPTURED", captured.pledges.single().entries.last().payload["state"].asString())
            assertEquals(before.entries, captured.pledges.single().entries.dropLast(1))
            fixture.context.getBean(CampaignPayments::class.java).save(fixture.pledges.findById(saved.id).orElseThrow())
            assertEquals(captured, fixture.document())
            assertTrue(fixture.failures().isEmpty())
            val current = fixture.pledges.findById(saved.id).orElseThrow()
            val drifts = listOf(
                fixture.copy(current, amount = current.amount + 1),
                fixture.copy(current, quantity = current.qty + 1),
                fixture.copy(current, currency = "EUR"),
                fixture.copy(current, id = UUID.randomUUID()),
                fixture.copy(current, campaignId = UUID.randomUUID()),
                fixture.copy(current, createdAt = current.createdAt.plusNanos(1000)),
                fixture.copy(current).apply { state = PledgeState.CREATED },
                fixture.copy(current).apply { paypalOrderId = "different-order" },
                fixture.copy(current).apply { authorizationExpiresAt = authorizationExpiresAt!!.plusNanos(1000) },
            )
            drifts.forEach { drift ->
                assertFailsWith<IllegalArgumentException> { fixture.persistence.checkpoint { fixture.records.recordPledge(drift) } }
                assertEquals(captured, fixture.document())
            }
            fixture.assertUniqueConstraints(saved.id)
        }

    @Test
    fun `managed live analysis with nanoseconds remains intact after a fresh read`(@TempDir directory: Path) =
        postgres(directory, liveAnalysis = true) { fixture ->
            val pledge = fixture.authorize(1)
            val before = fixture.document()
            assertEquals("INTACT", before.pledges.single().integrity)
            val source = before.sources.single { it.sourceId == fixture.runId }
            assertEquals("2026-01-01T00:00:00.123457Z", source.payload["createdAt"].asString())
            fixture.gateway.fake.voidAuthorizationResults.add(PaymentResult.Received(
                VoidDetails(AuthorizationId(pledge.authorizationId!!)), PaymentStatus.VOIDED))
            assertEquals(PledgeState.VOIDED, fixture.success(fixture.service.cancel(fixture.ref)).pledges.single().state)
            assertEquals(before.sources, fixture.document().sources)
            assertEquals("INTACT", fixture.document().pledges.single().integrity)
            assertTrue(fixture.failures().isEmpty())
        }

    @Test
    fun `corrupt chain cannot roll back committed capture refund or remaining hold cleanup`(@TempDir directory: Path) =
        postgres(directory) { fixture ->
            val first = fixture.authorize(2)
            val second = fixture.authorize(1)
            val before = fixture.document().pledges.single { it.pledgeId == first.id }.entries
            fixture.persistence.checkpoint {
                fixture.entities.createNativeQuery("update decision_record_entries set hash = 'changed' where pledge_id = :id and position = 1")
                    .setParameter("id", first.id).executeUpdate()
            }
            fixture.gateway.fake.captureResults.add(PaymentResult.Received(fixture.capture(first), PaymentStatus.COMPLETED))
            fixture.gateway.fake.captureResults.add(PaymentResult.Failed(PaymentStatus.DECLINED))
            fixture.gateway.fake.refundCaptureResults.add(PaymentResult.Received(
                RefundDetails(RefundId("synthetic-refund"), Money(first.amount, first.currency), fixture.capture(first).captureId),
                PaymentStatus.COMPLETED))
            fixture.gateway.fake.voidAuthorizationResults.add(PaymentResult.Received(
                VoidDetails(AuthorizationId(second.authorizationId!!)), PaymentStatus.VOIDED))
            assertEquals(CampaignState.NOT_FUNDED, fixture.success(fixture.service.confirmProduction(fixture.ref)).state)
            assertEquals(PledgeState.REFUNDED, fixture.pledges.findById(first.id).orElseThrow().state)
            assertEquals(PledgeState.VOIDED, fixture.pledges.findById(second.id).orElseThrow().state)
            assertTrue(fixture.failures().isNotEmpty())
            assertTrue(fixture.failures().all { it.pledgeId == first.id && it.detail == "Decision record checkpoint failed." && it.paypalId == null })
            val corrupted = fixture.document().pledges.single { it.pledgeId == first.id }
            assertEquals("CHANGED", corrupted.integrity)
            assertEquals(before.map { it.payload }, corrupted.entries.map { it.payload })
        }

    @Test
    fun `timestamp rounding including second carry preserves committed payment snapshots`(@TempDir directory: Path) =
        postgres(directory) { fixture ->
            val times = listOf(
                "2026-01-01T00:00:00.123456789Z" to "2026-01-01T00:00:00.123457Z",
                "2026-01-01T00:00:00.999999789Z" to "2026-01-01T00:00:01Z",
                "2026-01-01T00:00:00.000000001Z" to "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00.123456499Z" to "2026-01-01T00:00:00.123456Z",
                "2026-01-01T00:00:00.123456500Z" to "2026-01-01T00:00:00.123457Z",
            )
            times.forEach { (input, expected) ->
                val pledge = Pledge(fixture.ref.campaignId, 1, 200, "USD", createdAt = Instant.parse(input),
                    authorizationExpiresAt = Instant.parse(input).plusSeconds(86_400))
                fixture.persistence.checkpoint { fixture.entities.persist(pledge) }
                fixture.persistence.checkpoint { fixture.records.recordPledge(pledge) }
                val saved = fixture.pledges.findById(pledge.id).orElseThrow()
                assertEquals(Instant.parse(expected), saved.createdAt)
                assertEquals(Instant.parse(expected).plusSeconds(86_400), saved.authorizationExpiresAt)
                val view = fixture.document().pledges.single { it.pledgeId == pledge.id }
                assertEquals("INTACT", view.integrity)
                assertEquals(expected, view.entries.last().payload["createdAt"].asString())
                assertEquals(saved.authorizationExpiresAt.toString(), view.entries.last().payload["authorizationExpiresAt"].asString())
            }
        }

    private fun postgres(directory: Path, liveAnalysis: Boolean = false, test: (Fixture) -> Unit) {
        val initdb = executable("initdb")
        val pgctl = executable("pg_ctl")
        assumeTrue(initdb != null && pgctl != null, "Optional PostgreSQL binaries are not installed")
        assumeTrue(System.getProperty("user.name") != "root", "PostgreSQL cannot initialize a cluster as root")
        val data = directory.resolve("data")
        run(directory, initdb!!, "-D", data.toString(), "-U", "postgres", "--auth=trust", "--no-locale", "--encoding=UTF8")
        val port = ServerSocket(0).use { it.localPort }
        Files.writeString(data.resolve("postgresql.conf"), "\nlisten_addresses = '127.0.0.1'\nport = $port\nunix_socket_directories = ''\n",
            StandardOpenOption.APPEND)
        val url = "jdbc:postgresql://127.0.0.1:$port/postgres"
        try {
            run(directory, pgctl!!, "-D", data.toString(), "-l", directory.resolve("server.log").toString(), "-w", "start")
            SpringApplicationBuilder(Application::class.java, CampaignServiceTests.PaymentTestConfiguration::class.java)
                .web(WebApplicationType.NONE).run(
                    "--spring.main.banner-mode=off", "--spring.datasource.url=$url",
                    "--spring.datasource.username=postgres", "--spring.datasource.password=",
                    "--spring.jpa.hibernate.ddl-auto=create", "--logging.level.root=WARN",
                    "--PAYPAL_CLIENT_ID=", "--PAYPAL_CLIENT_SECRET=", "--PAYPAL_WEBHOOK_ID=", "--LLM_API_KEY=", "--LLM_SEED_RUN=false",
                ).use { context -> test(Fixture(context, url, liveAnalysis)) }
        } finally {
            run(directory, pgctl!!, "-D", data.toString(), "-m", "immediate", "-w", "stop")
        }
    }

    private fun executable(name: String): String? = System.getenv("PATH").split(File.pathSeparator)
        .map { Path.of(it, name) }.firstOrNull(Files::isExecutable)?.toString()

    private fun run(directory: Path, vararg command: String) {
        val output = directory.resolve("command.log").toFile()
        val process = ProcessBuilder(*command).redirectErrorStream(true).redirectOutput(output).start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            error("Disposable PostgreSQL command timed out: ${command.first()}")
        }
        assertEquals(0, process.exitValue(), output.readText().takeLast(3000))
    }

    private class Fixture(val context: ConfigurableApplicationContext, private val url: String, liveAnalysis: Boolean) {
        val service = context.getBean(CampaignService::class.java)
        val persistence = context.getBean(CampaignPersistence::class.java)
        val entities = context.getBean(EntityManager::class.java)
        val records = context.getBean(DecisionRecordService::class.java)
        val pledges = context.getBean(PledgeRepository::class.java)
        val gateway = context.getBean(CrashableGateway::class.java)
        private val clock = context.getBean(MutableClock::class.java).apply { current = Instant.parse(if (liveAnalysis) "2026-01-01T00:00:00Z" else "2026-01-01T00:00:00.123456789Z") }
        val workspace: Workspace
        val ref: CampaignRef
        val runId: UUID

        init {
            val workspaces = context.getBean(WorkspaceService::class.java)
            workspace = workspaces.create()
            val seed = assertNotNull(context.getBean(AnalysisRunService::class.java).latest(workspace.token))
            runId = if (liveAnalysis) persistence.checkpoint {
                val live = AnalysisRun(workspace.id, "synthetic-live", seed.run.inputHash, seed.run.resultJson, createdAt = Instant.parse("2026-01-01T00:00:00.123456789Z"))
                entities.persist(live)
                records.recordAnalysis(live, workspaces.listWishes(workspace.token))
                live.id
            } else seed.run.id
            val proposal = assertNotNull(seed.output.proposal)
            val option = workspaces.listOptions(workspace.token).single { it.id == proposal.optionId }
            val draft = success(service.createDraft(workspace.id, DraftOffer(option.id, runId, "Postcard batch", "Five postcards",
                proposal.suggestedVariants, option.unitCost, "Ships in two weeks", option.minBatch, option.maxCapacity, Duration.ofMinutes(15))))
            ref = CampaignRef(workspace.id, draft.campaignId)
            success(service.publish(ref, Duration.ofMinutes(15)))
        }

        fun authorize(quantity: Int): Pledge {
            val order = OrderId("synthetic-order-${UUID.randomUUID()}")
            gateway.fake.createOrderResults.add(PaymentResult.Received(OrderDetails(order), PaymentStatus.CREATED))
            val started = success(service.startPledge(ref, quantity))
            gateway.fake.authorizeOrderResults.add(PaymentResult.Received(AuthorizationDetails(
                AuthorizationId("synthetic-authorization-${started.id}"), Money(started.amount, started.currency),
                expiresAt = Instant.parse("2026-01-02T00:00:00Z"), orderId = order), PaymentStatus.CREATED))
            success(service.completePledge(PledgeRef(ref, started.id)))
            clock.current = clock.current.plusSeconds(1)
            return pledges.findById(started.id).orElseThrow()
        }

        fun copy(pledge: Pledge, amount: Long = pledge.amount, campaignId: UUID = pledge.campaignId,
            createdAt: Instant = pledge.createdAt, quantity: Int = pledge.qty, currency: String = pledge.currency, id: UUID = pledge.id) =
            Pledge(campaignId, quantity, amount, currency, pledge.state, pledge.paypalOrderId, pledge.authorizationId,
                pledge.authorizationExpiresAt, pledge.captureId, pledge.refundId, pledge.payerEmailMasked, pledge.shippingAddress,
                pledge.lastError, createdAt, id)

        fun capture(pledge: Pledge) = CaptureDetails(CaptureId("synthetic-capture-${pledge.id}"), Money(pledge.amount, pledge.currency),
            AuthorizationId(pledge.authorizationId!!))
        fun document() = assertNotNull(context.getBean(DecisionRecordQuery::class.java).findByToken(workspace.token))
        fun failures() = context.getBean(EventRepository::class.java).findByCampaignIdOrderByCreatedAtAscIdAsc(ref.campaignId)
            .filter { it.type == "PROVENANCE_RECORD_FAILED" }
        fun <T> success(result: ServiceResult<T>): T = assertIs<ServiceResult.Success<T>>(result).value

        fun assertUniqueConstraints(pledgeId: UUID) {
            DriverManager.getConnection(url, "postgres", "").use { connection ->
                val statements = listOf(
                    "insert into pledge_decision_records select * from pledge_decision_records where pledge_id = '$pledgeId'",
                    "insert into decision_record_entries (id, pledge_id, position, kind, payload, previous_hash, hash) " +
                        "select '${UUID.randomUUID()}', pledge_id, position, kind, payload, previous_hash, hash from decision_record_entries where pledge_id = '$pledgeId' and position = 1",
                    "insert into decision_source_snapshots (id, workspace_id, source_type, source_id, payload, hash) " +
                        "select '${UUID.randomUUID()}', workspace_id, source_type, source_id, payload, hash from decision_source_snapshots where source_id = '$runId'",
                )
                statements.forEach { sql ->
                    val failure = assertFailsWith<SQLException> { connection.createStatement().use { it.executeUpdate(sql) } }
                    assertEquals("23505", failure.sqlState)
                }
            }
            assertFalse(document().pledges.isEmpty())
        }
    }
}

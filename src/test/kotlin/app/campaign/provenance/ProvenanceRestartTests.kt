package app.campaign.provenance

import app.Application
import app.campaign.analysisrun.AnalysisRunService
import app.campaign.model.Money
import app.campaign.model.PledgeState
import app.campaign.port.AuthorizationDetails
import app.campaign.port.AuthorizationId
import app.campaign.port.OrderDetails
import app.campaign.port.OrderId
import app.campaign.port.PaymentResult
import app.campaign.port.PaymentStatus
import app.campaign.port.VoidDetails
import app.campaign.service.CampaignPayments
import app.campaign.service.CampaignRef
import app.campaign.service.CampaignService
import app.campaign.service.CampaignServiceTests
import app.campaign.service.CrashableGateway
import app.campaign.service.DraftOffer
import app.campaign.service.PledgeRef
import app.campaign.service.ServiceResult
import app.campaign.store.PledgeRepository
import app.campaign.workspace.WorkspaceService
import app.paypal.PayPalProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProvenanceRestartTests {
    @Test
    fun `application restart retains source hashes and continues the same pledge chain`(@TempDir directory: Path) {
        val url = "jdbc:h2:file:${directory.resolve("records")}"
        val before = application(url, "update").use { context ->
            val workspaces = context.getBean(WorkspaceService::class.java)
            val workspace = workspaces.create()
            val run = assertNotNull(context.getBean(AnalysisRunService::class.java).latest(workspace.token))
            val proposal = assertNotNull(run.output.proposal)
            val option = workspaces.listOptions(workspace.token).single { it.id == proposal.optionId }
            val service = context.getBean(CampaignService::class.java)
            val draft = success(service.createDraft(workspace.id, DraftOffer(option.id, run.run.id, "Postcard batch", "Five postcards",
                proposal.suggestedVariants, option.unitCost, "Ships in two weeks", option.minBatch, option.maxCapacity,
                Duration.ofMinutes(15))))
            val ref = CampaignRef(workspace.id, draft.campaignId)
            success(service.publish(ref, Duration.ofMinutes(15)))
            val gateway = context.getBean(CrashableGateway::class.java)
            gateway.fake.createOrderResults.add(PaymentResult.Received(OrderDetails(OrderId("restart-order")), PaymentStatus.CREATED))
            val pledge = success(service.startPledge(ref, 1))
            gateway.fake.authorizeOrderResults.add(PaymentResult.Received(AuthorizationDetails(
                AuthorizationId("restart-authorization"), Money(pledge.amount, pledge.currency), expiresAt = null,
                orderId = OrderId("restart-order")), PaymentStatus.CREATED))
            success(service.completePledge(PledgeRef(ref, pledge.id)))
            workspace.token to assertNotNull(context.getBean(DecisionRecordQuery::class.java).findByToken(workspace.token))
        }
        application(url, "validate").use { context ->
            val query = context.getBean(DecisionRecordQuery::class.java)
            val restored = assertNotNull(query.findByToken(before.first))
            assertEquals(before.second, restored)
            assertEquals("INTACT", restored.pledges.single().integrity)
            val record = restored.pledges.single()
            val pledge = context.getBean(PledgeRepository::class.java).findById(record.pledgeId).orElseThrow()
            assertEquals("restart-order", pledge.paypalOrderId)
            assertEquals("restart-authorization", pledge.authorizationId)
            context.getBean(CampaignPayments::class.java).save(pledge)
            assertEquals(restored, query.findByToken(before.first))
            val gateway = context.getBean(CrashableGateway::class.java)
            assertTrue(gateway.fake.calls.isEmpty())
            gateway.fake.voidAuthorizationResults.add(PaymentResult.Received(
                VoidDetails(AuthorizationId("restart-authorization")), PaymentStatus.VOIDED))
            val ref = CampaignRef(restored.workspaceId, record.campaignId)
            assertEquals(PledgeState.VOIDED, success(context.getBean(CampaignService::class.java).cancel(ref)).pledges.single().state)
            val released = assertNotNull(query.findByToken(before.first))
            assertEquals(restored.sources, released.sources)
            assertEquals("INTACT", released.pledges.single().integrity)
            assertEquals(record.entries, released.pledges.single().entries.dropLast(1))
            assertEquals(record.lastHash, released.pledges.single().entries.last().previousHash)
        }
    }

    private fun application(url: String, ddl: String) =
        SpringApplicationBuilder(Application::class.java, CampaignServiceTests.PaymentTestConfiguration::class.java)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.main.banner-mode=off", "--spring.datasource.url=$url", "--spring.jpa.hibernate.ddl-auto=$ddl",
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--PAYPAL_BASE_URL=${PayPalProperties.SANDBOX_URL}", "--PAYPAL_CLIENT_ID=", "--PAYPAL_CLIENT_SECRET=",
                "--PAYPAL_WEBHOOK_ID=", "--LLM_API_KEY=", "--LLM_SEED_RUN=false",
            )

    private fun <T> success(result: ServiceResult<T>): T = assertIs<ServiceResult.Success<T>>(result).value
}

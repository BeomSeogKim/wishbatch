package app.campaign.store

import app.campaign.model.AnalysisRun
import app.campaign.model.Campaign
import app.campaign.model.Event
import app.campaign.model.Offer
import app.campaign.model.Option
import app.campaign.model.Pledge
import app.campaign.model.Wish
import app.campaign.model.WishSource
import app.campaign.model.Workspace
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest
class RepositoryTests @Autowired constructor(
    private val entityManager: EntityManager,
    private val workspaces: WorkspaceRepository,
    private val options: OptionRepository,
    private val wishes: WishRepository,
    private val analyses: AnalysisRunRepository,
    private val offers: OfferRepository,
    private val campaigns: CampaignRepository,
    private val pledges: PledgeRepository,
    private val events: EventRepository,
    private val transactionManager: PlatformTransactionManager,
) {
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `scoped queries exclude records from another workspace`() {
        val workspace = workspaces.save(Workspace("repository-workspace", now))
        val other = workspaces.save(Workspace("other-repository-workspace", now))
        val option = options.save(Option(workspace.id, "Print", "print", emptyMap(), emptyMap(),
            100, "USD", 1, 10, "Two weeks", "US"))
        val wish = wishes.save(Wish(workspace.id, "A print", WishSource.ONSITE, createdAt = now))
        val analysis = analyses.save(AnalysisRun(workspace.id, "synthetic-model", "hash", "{}", createdAt = now))
        val offer = offers.save(Offer(workspace.id, option.id, analysis.id, "Print", "A5 print",
            emptyMap(), 200, "USD", "Shipping included", "US", 1, 10))
        val campaign = campaigns.save(Campaign(offer.id))
        entityManager.flush()
        entityManager.clear()

        assertEquals(workspace.id, workspaces.findByToken(workspace.token)?.id)
        assertEquals(workspace.id, workspaces.lockById(workspace.id)?.id)
        assertNull(workspaces.lockById(UUID.randomUUID()))
        assertNotNull(options.findByIdAndWorkspaceId(option.id, workspace.id))
        assertNotNull(wishes.findByIdAndWorkspaceId(wish.id, workspace.id))
        assertNotNull(analyses.findByIdAndWorkspaceId(analysis.id, workspace.id))
        assertNotNull(offers.findByIdAndWorkspaceId(offer.id, workspace.id))
        assertNull(options.findByIdAndWorkspaceId(option.id, other.id))
        assertNull(wishes.findByIdAndWorkspaceId(wish.id, other.id))
        assertNull(analyses.findByIdAndWorkspaceId(analysis.id, other.id))
        assertNull(offers.findByIdAndWorkspaceId(offer.id, other.id))
        assertEquals(listOf(option.id), options.findByWorkspaceId(workspace.id).map { it.id })
        assertEquals(listOf(wish.id), wishes.findByWorkspaceId(workspace.id).map { it.id })
        assertEquals(listOf(analysis.id), analyses.findByWorkspaceId(workspace.id).map { it.id })
        assertEquals(listOf(campaign.id), campaigns.findByOfferIdIn(offers.findByWorkspaceId(workspace.id).map { it.id }).map { it.id })
        assertTrue(campaigns.findByOfferIdIn(offers.findByWorkspaceId(other.id).map { it.id }).isEmpty())
    }

    @Test
    fun `pledges and events have stable chronological order within one campaign`() {
        val campaignId = UUID.randomUUID()
        val firstId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val secondId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val thirdId = UUID.fromString("00000000-0000-0000-0000-000000000003")
        pledges.saveAll(listOf(
            Pledge(campaignId, 1, 200, "USD", createdAt = now.plusSeconds(1), id = thirdId),
            Pledge(campaignId, 1, 200, "USD", createdAt = now, id = secondId),
            Pledge(campaignId, 1, 200, "USD", createdAt = now, id = firstId),
            Pledge(UUID.randomUUID(), 1, 200, "USD", createdAt = now.minusSeconds(1)),
        ))
        events.saveAll(listOf(
            Event(campaignId, "LATER", createdAt = now.plusSeconds(1), id = thirdId),
            Event(campaignId, "SECOND", createdAt = now, id = secondId),
            Event(campaignId, "FIRST", createdAt = now, id = firstId),
            Event(UUID.randomUUID(), "UNRELATED", createdAt = now.minusSeconds(1)),
        ))
        entityManager.flush()
        entityManager.clear()

        assertEquals(listOf(firstId, secondId, thirdId), pledges.findByCampaignIdOrderByCreatedAtAscIdAsc(campaignId).map { it.id })
        assertEquals(listOf(firstId, secondId, thirdId), events.findByCampaignIdOrderByCreatedAtAscIdAsc(campaignId).map { it.id })
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `workspace lock serializes concurrent transactions`() {
        val transaction = TransactionTemplate(transactionManager)
        val workspace = transaction.execute { workspaces.saveAndFlush(Workspace("locking-workspace", now)) }
        val firstLocked = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondLocked = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit {
                transaction.executeWithoutResult {
                    assertNotNull(workspaces.lockById(workspace.id))
                    firstLocked.countDown()
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(firstLocked.await(5, TimeUnit.SECONDS))
            val second = executor.submit {
                transaction.executeWithoutResult {
                    secondStarted.countDown()
                    assertNotNull(workspaces.lockById(workspace.id))
                    secondLocked.countDown()
                }
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertFalse(secondLocked.await(100, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertEquals(0L, secondLocked.count)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
            transaction.executeWithoutResult { workspaces.deleteById(workspace.id) }
        }
    }
}

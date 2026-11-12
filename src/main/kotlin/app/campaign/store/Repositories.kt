package app.campaign.store

import app.campaign.model.AnalysisRun
import app.campaign.model.Campaign
import app.campaign.model.CampaignState
import app.campaign.model.Event
import app.campaign.model.Offer
import app.campaign.model.Option
import app.campaign.model.Pledge
import app.campaign.model.Wish
import app.campaign.model.Workspace
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface WorkspaceRepository : JpaRepository<Workspace, UUID> {
    fun findByToken(token: String): Workspace?

    /** Hold this lock in a transaction while publishing or checking and updating capacity. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Workspace w where w.id = :id")
    fun lockById(@Param("id") id: UUID): Workspace?
}

interface OptionRepository : JpaRepository<Option, UUID> {
    fun findByIdAndWorkspaceId(id: UUID, workspaceId: UUID): Option?
    fun findByWorkspaceId(workspaceId: UUID): List<Option>
}

interface WishRepository : JpaRepository<Wish, UUID> {
    fun findByIdAndWorkspaceId(id: UUID, workspaceId: UUID): Wish?
    fun findByWorkspaceId(workspaceId: UUID): List<Wish>
}

interface AnalysisRunRepository : JpaRepository<AnalysisRun, UUID> {
    fun findByIdAndWorkspaceId(id: UUID, workspaceId: UUID): AnalysisRun?
    fun findByWorkspaceId(workspaceId: UUID): List<AnalysisRun>
}

interface OfferRepository : JpaRepository<Offer, UUID> {
    fun findByIdAndWorkspaceId(id: UUID, workspaceId: UUID): Offer?
    fun findByWorkspaceId(workspaceId: UUID): List<Offer>
}

interface CampaignRepository : JpaRepository<Campaign, UUID> {
    @Query("select c from Campaign c, Offer o where c.offerId = o.id and o.workspaceId = :workspaceId")
    fun findByWorkspaceId(@Param("workspaceId") workspaceId: UUID): List<Campaign>

    @Query("select count(c) > 0 from Campaign c, Offer o, Workspace w " +
        "where c.offerId = o.id and o.workspaceId = w.id and w.token = :token " +
        "and c.state = :state and c.closesAt > :now")
    fun existsOpenByToken(
        @Param("token") token: String,
        @Param("state") state: CampaignState,
        @Param("now") now: Instant,
    ): Boolean

    fun findByOfferIdIn(offerIds: Collection<UUID>): List<Campaign>
}

interface PledgeRepository : JpaRepository<Pledge, UUID> {
    fun findByCampaignIdOrderByCreatedAtAscIdAsc(campaignId: UUID): List<Pledge>
}

interface EventRepository : JpaRepository<Event, UUID> {
    fun findByCampaignIdOrderByCreatedAtAscIdAsc(campaignId: UUID): List<Event>
}

package app.campaign.reading

import app.campaign.model.*
import app.campaign.port.AnalysisFailure
import app.campaign.terms.SealedOfferChanged
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalysisResult
import app.campaign.port.WishAnalyzer
import app.campaign.provenance.DecisionRecordHash
import app.campaign.provenance.DecisionRecordService
import app.campaign.provenance.DecisionSourceSnapshot
import app.campaign.provenance.PledgeProvenanceCheckpoint
import app.campaign.service.CampaignPersistence
import app.campaign.store.CampaignRepository
import app.campaign.store.OfferRepository
import app.campaign.store.PledgeRepository
import app.campaign.store.WorkspaceRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.UUID

@Service
class OfferWishReadingService(
    private val entities: EntityManager,
    private val persistence: CampaignPersistence,
    private val records: DecisionRecordService,
    private val provenance: PledgeProvenanceCheckpoint,
    private val workspaces: WorkspaceRepository,
    private val campaigns: CampaignRepository,
    private val offers: OfferRepository,
    private val pledges: PledgeRepository,
    private val analyzer: WishAnalyzer,
    private val mapper: JsonMapper,
    private val clock: Clock,
) {
    private val hashing = DecisionRecordHash(mapper)
    private data class Reservation(val reading: OfferWishReading, val option: Option, val wish: Wish, val attempt: Int)

    /** POST-only: at most two durable reservations, each using the adapter's one-call deadline. */
    fun assess(workspaceId: UUID, offerId: UUID, wishId: UUID): JsonNode? {
        val reservation = persistence.checkpoint {
            val offer = entities.find(Offer::class.java, offerId, LockModeType.PESSIMISTIC_WRITE)
                ?.takeIf { it.workspaceId == workspaceId } ?: return@checkpoint null
            val wish = entities.find(Wish::class.java, wishId)?.takeIf { it.workspaceId == workspaceId }
                ?: return@checkpoint null
            val campaign = campaigns.findByOfferIdIn(listOf(offerId)).singleOrNull() ?: return@checkpoint null
            if (campaign.openedAt == null) return@checkpoint null
            val run = entities.find(AnalysisRun::class.java, offer.analysisRunId)
            if (run != null && mapper.readValue(run.resultJson, AnalysisOutput::class.java).wishes.any { it.wishId == wish.id }) {
                return@checkpoint null
            }
            val seal = try { records.sealedOffer(workspaceId, offerId) } catch (_: SealedOfferChanged) { null }
                ?: return@checkpoint null
            val option = entities.find(Option::class.java, offer.optionId)?.takeIf { it.workspaceId == workspaceId }
                ?: return@checkpoint null
            val input = readingInput(hashing, option, wish, offer, seal.termsHash)
            val existing = find(offerId, wishId, lock = true)
            val reading = existing ?: OfferWishReading(workspaceId, offerId, wishId, hashing.sha256(input), input)
                .also(entities::persist)
            if (reading.workspaceId != workspaceId || reading.inputJson != input || reading.inputHash != hashing.sha256(input) ||
                reading.completed || reading.attempts >= 2 || reading.reservedUntil?.isAfter(clock.instant()) == true) {
                return@checkpoint null
            }
            reading.attempts++
            reading.reservedUntil = clock.instant().plusSeconds(30)
            saveSource(reading, null, "IN_PROGRESS")
            Reservation(reading, option, wish, reading.attempts)
        }
        if (reservation != null) {
            val result = try { analyzer.classify(reservation.option, reservation.wish) } catch (_: Exception) {
                AnalysisResult.Failure(AnalysisFailure.PROVIDER_FAILURE)
            }
            persistence.checkpoint {
                val reading = entities.find(OfferWishReading::class.java, reservation.reading.id, LockModeType.PESSIMISTIC_WRITE)
                if (reading.attempts == reservation.attempt && !reading.completed) {
                    val input = mapper.readTree(reading.inputJson)
                    val success = (result as? AnalysisResult.Success)?.takeIf {
                        it.inputHash == input.get("classifierInputHash").asString() && it.model.isNotBlank()
                    }
                    val output = success?.validated?.output
                    val wish = output?.wishes?.singleOrNull { it.wishId == reading.wishId }
                    val verdict = output?.verdicts?.singleOrNull {
                        it.wishId == reading.wishId && it.optionId == reservation.option.id
                    }
                    val needs = wish?.needs.orEmpty().filter { it.quote.isNotBlank() && reservation.wish.text.contains(it.quote) }
                    val evidence = needs.filter { it.strength != NeedStrength.PREFERENCE }.ifEmpty { needs }
                    val quote = if (evidence.isEmpty()) null else reservation.wish.text.substring(
                        evidence.minOf { reservation.wish.text.indexOf(it.quote) },
                        evidence.maxOf { reservation.wish.text.indexOf(it.quote) + it.quote.length },
                    )
                    val valid = success != null && wish != null &&
                        (wish.kind == WishKind.NOT_A_REQUEST || (verdict != null && quote != null))
                    val assessment = if (!valid) null else mapOf(
                        "kind" to wish!!.kind, "verdict" to verdict?.verdict, "quote" to quote,
                        "reason" to verdict?.reason, "model" to success!!.model,
                        "completedAt" to success.completedAt.toString(),
                    )
                    reading.completed = valid
                    reading.reservedUntil = null
                    saveSource(reading, assessment, if (valid) null else
                        (result as? AnalysisResult.Failure)?.reason?.name ?: "INVALID_OUTPUT")
                }
            }
        }
        return current(workspaceId, offerId, wishId)
    }

    fun assessForCampaign(workspaceId: UUID, campaignId: UUID, wishId: UUID): JsonNode? {
        val campaign = campaigns.findById(campaignId).orElse(null)?.takeIf {
            it.state == CampaignState.OPEN && it.closesAt?.isAfter(clock.instant()) == true
        } ?: return null
        return assess(workspaceId, campaign.offerId, wishId)
    }

    fun assessOpen(token: String, wishId: UUID): JsonNode? {
        val workspace = workspaces.findByToken(token) ?: return null
        val campaign = campaigns.findByWorkspaceId(workspace.id).singleOrNull {
            it.state == CampaignState.OPEN && it.closesAt?.isAfter(clock.instant()) == true
        } ?: return null
        return assess(workspace.id, campaign.offerId, wishId)
    }

    /** The explicit retry is scoped to the stored pledge, never to caller-supplied wish/offer IDs. */
    fun retry(token: String, pledgeId: UUID): Boolean {
        val workspace = workspaces.findByToken(token) ?: return false
        val pledge = pledges.findById(pledgeId).orElse(null) ?: return false
        val campaign = campaigns.findById(pledge.campaignId).orElse(null) ?: return false
        val offer = offers.findByIdAndWorkspaceId(campaign.offerId, workspace.id) ?: return false
        val wishId = pledge.wishId ?: return false
        assess(workspace.id, offer.id, wishId) ?: return false
        provenance.linkReading(pledge)
        return true
    }

    private fun current(workspaceId: UUID, offerId: UUID, wishId: UUID): JsonNode? = persistence.checkpoint {
        val reading = find(offerId, wishId)?.takeIf { it.workspaceId == workspaceId } ?: return@checkpoint null
        val source = reading.sourceId?.let { id -> entities.createQuery(
            "select s from DecisionSourceSnapshot s where s.sourceType = 'OFFER_WISH_READING' and s.sourceId = :id",
            DecisionSourceSnapshot::class.java,
        ).setParameter("id", id).resultList.singleOrNull() } ?: return@checkpoint null
        if (!records.readingSourceValid(source, workspaceId, offerId, wishId)) return@checkpoint null
        mapper.readTree(source.payload)
    }

    private fun find(offerId: UUID, wishId: UUID, lock: Boolean = false): OfferWishReading? = entities.createQuery(
        "select r from OfferWishReading r where r.offerId = :offer and r.wishId = :wish", OfferWishReading::class.java,
    ).setParameter("offer", offerId).setParameter("wish", wishId)
        .also { if (lock) it.setLockMode(LockModeType.PESSIMISTIC_WRITE) }.resultList.singleOrNull()

    private fun saveSource(reading: OfferWishReading, assessment: Map<String, Any?>?, failure: String?) {
        val id = UUID.randomUUID()
        val input = mapper.readTree(reading.inputJson)
        val payload = hashing.canonical(mapOf(
            "assessmentId" to id, "scope" to "FORMAT_ASSESSMENT", "workspaceId" to reading.workspaceId,
            "offerId" to reading.offerId, "wishId" to reading.wishId, "optionId" to input.get("optionId").asString(),
            "termsHash" to input.get("termsHash").asString(), "inputHash" to reading.inputHash, "input" to input,
            "availability" to if (assessment == null) "PENDING" else "AVAILABLE",
            "attempts" to reading.attempts, "retryAllowed" to (assessment == null && reading.attempts < 2),
            "failureReason" to failure,
        ) + (assessment ?: emptyMap()))
        entities.persist(DecisionSourceSnapshot(reading.workspaceId, "OFFER_WISH_READING", id, payload, hashing.sha256(payload)))
        reading.sourceId = id
    }
}

package app.campaign

import app.campaign.model.CampaignReason
import app.campaign.model.CampaignState
import app.campaign.model.PledgeState
import app.campaign.rules.CAMPAIGN_TRANSITIONS
import app.campaign.rules.CampaignTransition
import app.campaign.rules.PLEDGE_TRANSITIONS
import app.campaign.rules.PledgeTransition
import app.campaign.rules.requireCampaignTransition
import app.campaign.rules.requirePledgeTransition
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TransitionTests {
    @Test
    fun `campaign graph rejects every unspecified state and reason combination`() {
        val expected = setOf(
            CampaignTransition(CampaignState.DRAFT, CampaignState.OPEN),
            CampaignTransition(CampaignState.OPEN, CampaignState.SETTLING),
            CampaignTransition(CampaignState.OPEN, CampaignState.AWAITING_CONFIRMATION),
            CampaignTransition(CampaignState.OPEN, CampaignState.NOT_FUNDED, CampaignReason.THRESHOLD_NOT_MET),
            CampaignTransition(CampaignState.OPEN, CampaignState.NOT_FUNDED, CampaignReason.CANCELLED_BY_MAKER),
            CampaignTransition(CampaignState.AWAITING_CONFIRMATION, CampaignState.SETTLING),
            CampaignTransition(CampaignState.AWAITING_CONFIRMATION, CampaignState.NOT_FUNDED, CampaignReason.CONFIRMATION_EXPIRED),
            CampaignTransition(CampaignState.AWAITING_CONFIRMATION, CampaignState.NOT_FUNDED, CampaignReason.CANCELLED_BY_MAKER),
            CampaignTransition(CampaignState.SETTLING, CampaignState.FUNDED),
            CampaignTransition(CampaignState.SETTLING, CampaignState.NOT_FUNDED, CampaignReason.CAPTURE_SHORTFALL),
        )
        assertEquals(expected, CAMPAIGN_TRANSITIONS)
        for (from in CampaignState.entries) {
            for (to in CampaignState.entries) {
                for (reason in listOf(null) + CampaignReason.entries) {
                    if (CampaignTransition(from, to, reason) in expected) {
                        assertEquals(to, requireCampaignTransition(from, to, reason))
                    } else {
                        assertFailsWith<IllegalArgumentException>("$from -> $to ($reason)") {
                            requireCampaignTransition(from, to, reason)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `pledge graph rejects shortcuts and terminal state changes`() {
        val expected = mapOf(
            PledgeState.CREATED to setOf(PledgeState.AUTHORIZED, PledgeState.ABANDONED, PledgeState.VOIDED, PledgeState.VOID_FAILED),
            PledgeState.AUTHORIZED to setOf(PledgeState.CAPTURED, PledgeState.CAPTURE_PENDING, PledgeState.CAPTURE_FAILED, PledgeState.VOIDED, PledgeState.VOID_FAILED),
            PledgeState.CAPTURED to setOf(PledgeState.REFUNDED, PledgeState.REFUND_PENDING, PledgeState.REFUND_FAILED, PledgeState.PARTIALLY_REFUNDED),
            PledgeState.CAPTURE_PENDING to setOf(PledgeState.CAPTURED, PledgeState.CAPTURE_FAILED),
            PledgeState.CAPTURE_FAILED to setOf(PledgeState.VOIDED, PledgeState.VOID_FAILED, PledgeState.CAPTURED, PledgeState.CAPTURE_PENDING),
            PledgeState.VOID_FAILED to setOf(PledgeState.VOIDED, PledgeState.CAPTURED, PledgeState.CAPTURE_PENDING),
            PledgeState.REFUND_PENDING to setOf(PledgeState.REFUNDED, PledgeState.REFUND_FAILED, PledgeState.PARTIALLY_REFUNDED),
            PledgeState.REFUND_FAILED to setOf(PledgeState.REFUNDED, PledgeState.REFUND_PENDING, PledgeState.PARTIALLY_REFUNDED),
            PledgeState.PARTIALLY_REFUNDED to setOf(PledgeState.REFUNDED),
        )
        assertEquals(expected.flatMap { (from, targets) -> targets.map { PledgeTransition(from, it) } }.toSet(), PLEDGE_TRANSITIONS)
        for (from in PledgeState.entries) {
            for (to in PledgeState.entries) {
                if (to in expected.getOrDefault(from, emptySet())) {
                    assertEquals(to, requirePledgeTransition(from, to))
                } else {
                    assertFailsWith<IllegalArgumentException>("$from -> $to") { requirePledgeTransition(from, to) }
                }
            }
        }
    }
}

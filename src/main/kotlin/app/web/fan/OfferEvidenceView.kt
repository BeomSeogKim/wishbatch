package app.web.fan

import app.campaign.service.OfferEvidence
import app.campaign.rules.OfferInterest

data class FanWishQuote(val quote: String)

data class OfferEvidenceView(val counts: OfferInterest, val quotes: List<FanWishQuote>) {
    companion object {
        fun from(evidence: OfferEvidence) = OfferEvidenceView(
            evidence.counts,
            evidence.quotes.map { FanWishQuote(it.quote) },
        )
    }
}

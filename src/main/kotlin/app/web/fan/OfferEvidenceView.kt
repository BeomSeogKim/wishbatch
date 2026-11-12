package app.web.fan

import app.campaign.service.OfferEvidence

data class FanWishQuote(val quote: String, val displayName: String)

data class OfferEvidenceView(val compatibleWishCount: Int, val quotes: List<FanWishQuote>) {
    companion object {
        fun from(evidence: OfferEvidence) = OfferEvidenceView(
            evidence.compatibleWishCount,
            evidence.quotes.map { FanWishQuote(it.quote, it.displayName ?: "Anonymous fan") },
        )
    }
}

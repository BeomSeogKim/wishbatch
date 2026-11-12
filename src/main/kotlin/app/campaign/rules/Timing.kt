package app.campaign.rules

import java.time.Duration
import java.time.Instant

val ALLOWED_CAMPAIGN_DURATIONS: Set<Duration> = setOf(
    Duration.ofMinutes(15), Duration.ofHours(2), Duration.ofHours(24), Duration.ofHours(48),
)

/** Durations are a closed set, not any duration below the maximum. */
fun closesAt(openedAt: Instant, duration: Duration): Instant {
    require(duration in ALLOWED_CAMPAIGN_DURATIONS) { "Unsupported campaign duration" }
    return openedAt.plus(duration)
}

/** Confirmation is allowed through this instant, inclusive; it expires strictly after it. */
fun confirmDeadline(closesAt: Instant): Instant = closesAt.plus(Duration.ofHours(12))

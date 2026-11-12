package app.web.security

import java.time.Clock
import java.time.Duration
import java.time.Instant

internal class RequestRateLimiter(
    private val limit: Int,
    private val window: Duration,
    private val clock: Clock = Clock.systemUTC(),
    private val maxKeys: Int = 10_000,
) {
    private data class Bucket(val expires: Instant, var count: Int)
    private val buckets = mutableMapOf<String, Bucket>()

    @Synchronized
    fun allow(key: String): Boolean {
        val now = clock.instant()
        buckets.entries.removeIf { !it.value.expires.isAfter(now) }
        val bucket = buckets[key] ?: run {
            // Fail closed when all slots are live, rather than evicting a client's quota.
            if (buckets.size >= maxKeys) return false
            Bucket(now.plus(window), 0).also { buckets[key] = it }
        }
        if (bucket.count >= limit) return false
        bucket.count++
        return true
    }
}

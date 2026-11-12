package app.web.security

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestRateLimiterTests {
    @Test
    fun `limits are independent by key and expire at the exact window boundary`() {
        val clock = MutableClock()
        val limiter = RequestRateLimiter(2, Duration.ofMinutes(1), clock)
        assertTrue(limiter.allow("first"))
        assertTrue(limiter.allow("first"))
        assertFalse(limiter.allow("first"))
        assertTrue(limiter.allow("second"))
        clock.advance(Duration.ofSeconds(59))
        assertFalse(limiter.allow("first"))
        clock.advance(Duration.ofSeconds(1))
        assertTrue(limiter.allow("first"))
        assertTrue(limiter.allow("first"))
        assertFalse(limiter.allow("first"))
    }

    @Test
    fun `full key storage rejects new clients without evicting live quotas`() {
        val clock = MutableClock()
        val limiter = RequestRateLimiter(1, Duration.ofMinutes(1), clock, maxKeys = 2)
        assertTrue(limiter.allow("first"))
        assertTrue(limiter.allow("second"))
        repeat(20) { assertFalse(limiter.allow("new-$it")) }
        assertFalse(limiter.allow("first"))
        assertFalse(limiter.allow("second"))
        clock.advance(Duration.ofMinutes(1))
        assertTrue(limiter.allow("new-client"))
        assertTrue(limiter.allow("first"))
        assertFalse(limiter.allow("another-client"))
    }

    private class MutableClock : Clock() {
        private var now = Instant.parse("2026-01-01T00:00:00Z")
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }
    }
}

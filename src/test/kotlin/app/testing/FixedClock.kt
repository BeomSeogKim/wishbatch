package app.testing

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

fun fixedClock(instant: Instant, zone: ZoneId = ZoneOffset.UTC): Clock = Clock.fixed(instant, zone)

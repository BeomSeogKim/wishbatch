package app.analysis

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/** A process-local UTC daily pool for gloss and offer-copy transport attempts only. */
internal class AuxiliaryCallBudget(private val clock: Clock = Clock.systemUTC()) {
    private var day: LocalDate? = null
    private var calls = 0

    @Synchronized
    fun reserve(dailyRunCap: Int): Boolean {
        val today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate()
        if (day != today) {
            day = today
            calls = 0
        }
        if (calls >= minOf(20, dailyRunCap)) return false
        calls++
        return true
    }
}

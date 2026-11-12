package app.campaign.rules

object MoneyInput {
    private val decimal = Regex("[0-9]+(?:\\.[0-9]{1,2})?")

    /** Parses nonnegative two-decimal money exactly; invalid or overflowing input returns null. */
    fun parseMinorUnits(raw: String): Long? {
        val value = raw.trim()
        if (!decimal.matches(value)) return null
        val whole = value.substringBefore('.')
        val fraction = value.substringAfter('.', "").padEnd(2, '0')
        return (whole + fraction).toLongOrNull()
    }

    fun formatMinorUnits(amount: Long): String {
        require(amount >= 0) { "Amount must be nonnegative" }
        return "${amount / 100}.${(amount % 100).toString().padStart(2, '0')}"
    }
}

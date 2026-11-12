package app.analysis

import app.campaign.model.Option

internal object RationaleNumbers {
    private const val WORDS = "zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|" +
        "thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|" +
        "sixty|seventy|eighty|ninety|hundred|thousand|million|billion|trillion|dozen|dozens|" +
        "hundreds|thousands|millions|billions|trillions|half|halves|quarter|quarters|couple|" +
        "single|double|triple|once|twice|thrice|first|second|third|fourth|fifth|sixth|seventh|" +
        "eighth|ninth|tenth|eleventh|twelfth|thirteenth|fourteenth|fifteenth|sixteenth|" +
        "seventeenth|eighteenth|nineteenth|twentieth|thirtieth|fortieth|fiftieth|sixtieth|" +
        "seventieth|eightieth|ninetieth|hundredth|thousandth|millionth"
    private val number = Regex("(?iuU)\\p{N}|\\b(?:$WORDS)\\b")
    private val catalogNumber = Regex(
        "(?iuU)(?<![\\p{L}\\p{N}])(?:[\\p{L}]+\\p{N}[\\p{L}\\p{N}]*|" +
            "\\p{N}+[\\p{L}]+|(?:\\p{N}+|$WORDS)[ -]+[\\p{L}]+)(?![\\p{L}\\p{N}])",
    )

    fun containsNumber(text: String, option: Option): Boolean {
        val catalog = listOf(option.name) + option.fixedAttributes.values + option.allowedVariants.values.flatten()
        // Bare numeric attributes cannot authorize an audience count with the same value.
        val phrases = (catalog + catalog.flatMap { value -> catalogNumber.findAll(value).map { it.value }.toList() })
            .filter { value ->
                number.containsMatchIn(value) && number.replace(value, " ")
                    .replace(Regex("(?iuU)\\band\\b"), " ").any { it.isLetter() }
            }
            .distinct().sortedByDescending { it.length }
        val withoutCatalog = phrases.fold(text) { remaining, phrase ->
            remaining.replace(Regex("(?iuU)(?<![\\p{L}\\p{N}])${Regex.escape(phrase)}(?![\\p{L}\\p{N}])"), " ")
        }
        return number.containsMatchIn(withoutCatalog)
    }
}

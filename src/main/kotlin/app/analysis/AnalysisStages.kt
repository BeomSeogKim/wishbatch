package app.analysis

import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Option
import app.campaign.model.NeedStrength
import app.campaign.model.NeedAttribute
import app.campaign.port.AnalysisOutput
import app.campaign.rules.optionWishCounts
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.util.Currency
import java.util.Locale

internal object AnalysisStages {
    private val classificationFields = setOf("wishes", "verdicts")

    fun instructions(stage: AnalysisStage): String = when (stage) {
        AnalysisStage.CLASSIFY -> """

            This is the classification stage only. Return only wishes and verdicts, not a proposal.
            Extract concise needs, retaining every hard condition. Keep each
            reason under 70 characters when possible. Do not repeat the wish or option name.
            A stated product format is demand for that format; another format is not automatically
            a substitute. Explicit alternatives are allowed. Non-gating finish/subject preferences
            do not make a compatible format conditional. Do not invent unstated purchase conditions.
        """.trimIndent()
        AnalysisStage.PROPOSE -> """

            This is the proposal stage only. Classification and counts are already validated.
            Return only proposal, no_proposal_reason, not_served, questions_for_maker and alternatives.
            Counts cover ALL analyzed wishes; evidence is only a bounded sample, not a census.
            Use counts to choose, and sampled needs' verbatim quotes for up to three rationale items.
            Include up to four concise maker questions about unresolved conditions.
            Never reclassify wishes or invent missing evidence. Omitted incompatible wishes will
            be regrouped by code. Keep reasons and rationale short; do not enumerate every wish.
        """.trimIndent()
    }

    @Suppress("UNCHECKED_CAST")
    fun schema(options: List<Option>, stage: AnalysisStage): Map<String, Any> {
        val full = AnalysisPrompt.schema(options)
        val fields = (full.getValue("properties") as Map<String, Any>).filterKeys {
            (it in classificationFields) == (stage == AnalysisStage.CLASSIFY)
        }
        return full + mapOf("properties" to fields, "required" to fields.keys.toList())
    }

    fun emptyProposal(): Map<String, Any?> = mapOf(
        "proposal" to null, "no_proposal_reason" to "No proposal selected.",
        "not_served" to emptyList<Any>(), "questions_for_maker" to emptyList<Any>(),
        "alternatives" to emptyList<Any>(),
    )

    // The validator accepts provider wire names and major-unit evidence, not persisted port JSON.
    fun classification(output: AnalysisOutput): Map<String, Any> = mapOf(
        "wishes" to output.wishes.map { wish ->
            mapOf(
                "wish_id" to wish.wishId, "kind" to wish.kind, "needs" to wish.needs.map { need ->
                    mapOf("strength" to need.strength, "attribute" to need.attribute.name.lowercase(Locale.ROOT),
                        "value" to need.value, "quote" to need.quote)
                },
                "max_unit_price" to wish.maxUnitPrice?.let {
                    BigDecimal.valueOf(it.minorUnits, Currency.getInstance(it.currency).defaultFractionDigits)
                },
                "quantity" to wish.quantity, "region" to wish.region,
            )
        },
        "verdicts" to output.verdicts.map {
            mapOf("wish_id" to it.wishId, "option_id" to it.optionId, "verdict" to it.verdict, "reason" to it.reason)
        },
    )

    fun proposalInput(mapper: JsonMapper, options: List<Option>, output: AnalysisOutput): String {
        val wishes = output.wishes.associateBy { it.wishId }
        val evidence = options.associate { option ->
            option.id.toString() to CompatibilityVerdict.entries.flatMap { verdict ->
                val candidates = output.verdicts.filter { it.optionId == option.id && it.verdict == verdict }.toMutableList()
                val attributes = mutableSetOf<NeedAttribute>()
                val sample = buildList {
                    repeat(minOf(3, candidates.size)) {
                        val selected = candidates.maxBy { candidate ->
                            wishes.getValue(candidate.wishId).needs.filter { it.attribute !in attributes }
                                .sumOf { if (it.strength == NeedStrength.HARD) 3 else 1 }
                        }
                        add(selected)
                        attributes += wishes.getValue(selected.wishId).needs.map { it.attribute }
                        candidates.remove(selected)
                    }
                }
                sample.map { item ->
                    mapOf(
                        "wish_id" to item.wishId, "verdict" to item.verdict, "reason" to item.reason,
                        "needs" to wishes.getValue(item.wishId).needs.take(3).map {
                            mapOf("strength" to it.strength, "attribute" to it.attribute,
                                "value" to it.value.take(120), "quote" to it.quote.take(200))
                        },
                    )
                }
            }
        }
        return mapper.writeValueAsString(mapOf(
            "options" to mapper.readTree(AnalysisPrompt.input(mapper, options, emptyList()))["options"],
            "counts" to optionWishCounts(output), "evidence" to evidence,
        ))
    }
}

package app.analysis

import app.campaign.model.CompatibilityVerdict
import app.campaign.model.Money
import app.campaign.model.NeedAttribute
import app.campaign.model.NeedStrength
import app.campaign.model.Option
import app.campaign.model.Wish
import app.campaign.model.WishKind
import app.campaign.port.AnalysisAlternative
import app.campaign.port.AnalysisOutput
import app.campaign.port.AnalysisValidationFlag
import app.campaign.port.AnalysisValidationResult
import app.campaign.port.AnalysisValidationRule
import app.campaign.port.AnalyzedWish
import app.campaign.port.MakerQuestion
import app.campaign.port.NotServedGroup
import app.campaign.port.Proposal
import app.campaign.port.ProposalEvidence
import app.campaign.port.WishNeed
import app.campaign.port.WishVerdict
import java.util.Currency
import java.util.Locale
import java.util.UUID
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class AnalysisValidator(private val mapper: JsonMapper) {
    fun validate(json: String, options: List<Option>, wishes: List<Wish>): AnalysisValidationResult {
        val root = mapper.reader()
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .readTree(json)
        val flags = mutableListOf<AnalysisValidationFlag>()
        return try {
            validate(root, options, wishes, flags)
        } catch (_: InvalidOutput) {
            AnalysisValidationResult.Rejected(flags.toList())
        }
    }

    private fun validate(
        root: JsonNode,
        options: List<Option>,
        wishes: List<Wish>,
        flags: MutableList<AnalysisValidationFlag>,
    ): AnalysisValidationResult {
        checkOutput(root.isObject)
        val optionById = options.associateBy { it.id }
        val wishById = wishes.associateBy { it.id }
        fun flag(rule: AnalysisValidationRule, path: String, message: String) {
            flags += AnalysisValidationFlag(rule, path, message)
        }
        fun reference(node: JsonNode, field: String, known: Set<UUID>, path: String): UUID? {
            val id = node[field]?.takeIf { it.isString }?.asString()?.let {
                try { UUID.fromString(it) } catch (_: IllegalArgumentException) { null }
            }
            if (id == null || id !in known) {
                flag(AnalysisValidationRule.V1, path, "Unknown reference dropped.")
                return null
            }
            return id
        }
        fun wishIds(node: JsonNode, path: String): List<UUID> = node.array("wish_ids").mapIndexedNotNull { index, value ->
            val wrapper = mapper.createObjectNode().set("wish_id", value)
            reference(wrapper, "wish_id", wishById.keys, "$path.wish_ids[$index]")
        }.distinct()
        fun quote(node: JsonNode, wishId: UUID, path: String): String? {
            val value = node["quote"]?.takeIf { it.isString }?.asString()
            if (value == null || normalize(value).isEmpty() || !normalize(wishById.getValue(wishId).text).contains(normalize(value))) {
                flag(AnalysisValidationRule.V2, path, "Ungrounded quote dropped.")
                return null
            }
            return value
        }
        fun commercialFields(node: JsonNode, path: String) {
            if (node.isObject) node.properties().forEach { (key, value) ->
                if (key in COMMERCIAL_FIELDS) flag(AnalysisValidationRule.V3, path, "Model commercial term ignored.")
                commercialFields(value, path)
            }
            if (node.isArray) node.forEach { commercialFields(it, path) }
        }
        commercialFields(root, "output")
        val proposalNode = root["proposal"]
        val pluralProposals = root["proposals"]
        val proposalCount = when {
            proposalNode == null || proposalNode.isNull -> 0
            proposalNode.isArray -> proposalNode.size()
            proposalNode.isObject -> 1
            else -> throw InvalidOutput()
        } + when {
            pluralProposals == null || pluralProposals.isNull -> 0
            pluralProposals.isArray -> pluralProposals.size()
            else -> throw InvalidOutput()
        }
        if (proposalCount > 1) {
            flag(AnalysisValidationRule.V5, "proposal", "Multiple proposals rejected.")
            return AnalysisValidationResult.Rejected(flags.toList())
        }
        checkOutput(proposalNode != null && !proposalNode.isArray && pluralProposals == null)
        val analyzed = root.array("wishes").mapIndexedNotNull { index, item ->
            val path = "wishes[$index]"
            val id = reference(item, "wish_id", wishById.keys, "$path.wish_id") ?: return@mapIndexedNotNull null
            val kind = enumValue<WishKind>(item.text("kind"))
            val needs = item.array("needs").mapIndexedNotNull { needIndex, need ->
                val groundedQuote = quote(need, id, "$path.needs[$needIndex]") ?: return@mapIndexedNotNull null
                WishNeed(
                    enumValue<NeedStrength>(need.text("strength")),
                    enumValue<NeedAttribute>(need.text("attribute").uppercase(Locale.ROOT)),
                    need.text("value"),
                    groundedQuote,
                )
            }
            if (kind == WishKind.NOT_A_REQUEST) return@mapIndexedNotNull AnalyzedWish(id, kind, emptyList())
            val price = item["max_unit_price"]?.takeUnless { it.isNull }?.let { value ->
                checkOutput(value.isNumber)
                if (needs.none { it.attribute == NeedAttribute.PRICE }) null else {
                    val currency = options.map { it.currency }.distinct().singleOrNull()
                    currency?.let {
                        try {
                            val units = value.decimalValue().movePointRight(Currency.getInstance(it).defaultFractionDigits).longValueExact()
                            checkOutput(units >= 0)
                            Money(units, it)
                        } catch (_: IllegalArgumentException) { throw InvalidOutput() }
                        catch (_: ArithmeticException) { throw InvalidOutput() }
                    }
                }
            }
            val quantity = item["quantity"]?.takeUnless { it.isNull }?.let { value ->
                checkOutput(value.isIntegralNumber && value.canConvertToInt() && value.asInt() > 0)
                if (needs.any { it.attribute == NeedAttribute.QUANTITY }) value.asInt() else null
            }
            val region = item.optionalText("region")?.takeIf { needs.any { need -> need.attribute == NeedAttribute.REGION } }
            AnalyzedWish(id, kind, needs, price, quantity, region)
        }
        checkOutput(analyzed.map { it.wishId }.toSet() == wishById.keys && analyzed.size == wishById.size)
        val requestIds = analyzed.filter { it.kind == WishKind.REQUEST }.map { it.wishId }.toSet()
        val verdicts = root.array("verdicts").mapIndexedNotNull { index, item ->
            val id = reference(item, "wish_id", wishById.keys, "verdicts[$index].wish_id") ?: return@mapIndexedNotNull null
            val optionId = reference(item, "option_id", optionById.keys, "verdicts[$index].option_id") ?: return@mapIndexedNotNull null
            checkOutput(id in requestIds)
            val reason = item.text("reason")
            checkOutput(reason.length <= 140)
            WishVerdict(id, optionId, enumValue<CompatibilityVerdict>(item.text("verdict")), reason)
        }
        val expectedPairs = requestIds.flatMap { wishId -> optionById.keys.map { wishId to it } }.toSet()
        checkOutput(verdicts.map { it.wishId to it.optionId }.toSet() == expectedPairs && verdicts.size == expectedPairs.size)
        var droppedProposal = false
        val proposal = proposalNode?.takeUnless { it.isNull }?.let { item ->
            val optionId = reference(item, "option_id", optionById.keys, "proposal.option_id")
            if (optionId == null) {
                droppedProposal = true
                null
            } else {
                val variants = item["suggested_variants"] ?: throw InvalidOutput()
                checkOutput(variants.isObject)
                val allowed = optionById.getValue(optionId).allowedVariants
                val suggestions = variants.properties().mapNotNull { (key, value) ->
                    if (value.isNull) null else if (!value.isString || value.asString() !in allowed[key].orEmpty()) {
                        flag(AnalysisValidationRule.V4, "proposal.suggested_variants", "Unsupported variant dropped.")
                        null
                    } else key to value.asString()
                }.toMap()
                val rationale = item.array("rationale").mapIndexedNotNull { index, evidence ->
                    val id = reference(evidence, "wish_id", wishById.keys, "proposal.rationale[$index].wish_id")
                        ?: return@mapIndexedNotNull null
                    val groundedQuote = quote(evidence, id, "proposal.rationale[$index]") ?: return@mapIndexedNotNull null
                    ProposalEvidence(evidence.text("text"), id, groundedQuote)
                }
                Proposal(optionId, suggestions, rationale)
            }
        }
        val noProposalReason = if (proposal == null) {
            root.optionalText("no_proposal_reason")?.takeIf { it.isNotBlank() }
                ?: if (droppedProposal) "The proposed option is unavailable." else throw InvalidOutput()
        } else null
        val incompatible = verdicts.filter { it.optionId == proposal?.optionId && it.verdict == CompatibilityVerdict.INCOMPATIBLE }
        val reportedNotServed = root.array("not_served").flatMapIndexed { index, group ->
            group.text("label")
            group.text("reason")
            wishIds(group, "not_served[$index]")
        }
        if (reportedNotServed.toSet() != incompatible.map { it.wishId }.toSet() || reportedNotServed.size != reportedNotServed.toSet().size) {
            flag(AnalysisValidationRule.V6, "not_served", "Groups rebuilt from incompatible verdicts.")
        }
        val notServed = incompatible.groupBy { it.reason }.map { (reason, items) ->
            NotServedGroup("Not served", items.map { it.wishId }, reason)
        }
        val questions = root.array("questions_for_maker").mapIndexedNotNull { index, question ->
            val ids = wishIds(question, "questions_for_maker[$index]")
            if (ids.isEmpty() && question.array("wish_ids").isNotEmpty()) null else MakerQuestion(question.text("text"), ids)
        }
        val alternatives = root.array("alternatives").mapIndexedNotNull { index, item ->
            val id = reference(item, "option_id", optionById.keys, "alternatives[$index].option_id") ?: return@mapIndexedNotNull null
            checkOutput(id != proposal?.optionId)
            AnalysisAlternative(id, item.text("why_not_first"))
        }
        checkOutput(alternatives.size <= 2 && alternatives.map { it.optionId }.distinct().size == alternatives.size)
        return AnalysisValidationResult.Accepted(
            AnalysisOutput(analyzed, verdicts, proposal, noProposalReason, notServed, questions, alternatives, options.map { it.id }),
            flags.toList(),
        )
    }

    private class InvalidOutput : RuntimeException()

    private fun checkOutput(condition: Boolean) {
        if (!condition) throw InvalidOutput()
    }

    private fun JsonNode.array(name: String): List<JsonNode> {
        val value = this[name] ?: throw InvalidOutput()
        checkOutput(value.isArray)
        return value.toList()
    }

    private fun JsonNode.text(name: String): String {
        val value = this[name] ?: throw InvalidOutput()
        checkOutput(value.isString)
        return value.asString()
    }

    private fun JsonNode.optionalText(name: String): String? = this[name]?.takeUnless { it.isNull }?.let {
        checkOutput(it.isString)
        it.asString()
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T =
        enumValues<T>().firstOrNull { it.name == value } ?: throw InvalidOutput()

    private fun normalize(value: String): String = value.replace(Regex("(?U)\\s+"), " ").trim()

    private companion object {
        val COMMERCIAL_FIELDS = setOf(
            "price", "unit_price", "unit_cost", "cost", "min_batch", "minimum_batch", "threshold",
            "quantity_threshold", "capacity", "max_capacity", "date", "delivery_date", "delivery_terms", "lead_time_text",
        )
    }
}

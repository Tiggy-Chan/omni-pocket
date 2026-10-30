package com.tiggy.qwenimage.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.ceil

const val DECISION_NONE_OPTION = "none of the options listed here"
const val DECISION_NONE_DESCRIPTION = "The correct option is not among the options listed in this question."
private const val NOUL_TRUE = "the statement is true / the answer is yes"
private const val NOUL_FALSE = "the statement is false / the answer is no"
private val excludedSlotLabels = setOf(
    "BQ", "BZ", "CJ", "CQ", "CZ", "DQ", "DZ", "EJ", "EY", "FJ", "FQ", "FV", "FZ",
    "GJ", "GK", "GQ", "GZ", "HJ", "IY", "JF", "JG", "JH", "JL", "JN", "JQ"
)

fun decisionSlotLabels(count: Int = 256): List<String> {
    require(count in 1..256)
    return buildList {
        for (letter in 'A'..'Z') add(letter.toString())
        for (first in 'A'..'Z') {
            for (second in 'A'..'Z') {
                val label = "$first$second"
                if (label !in excludedSlotLabels && size < count) add(label)
            }
        }
    }.take(count)
}

enum class DecisionKind(val json: String) {
    Choice("choice"), Noul("noul"), Score("score")
}

data class DecisionOption(val name: String = "", val description: String)

data class DecisionQuestion(
    val id: String,
    val kind: DecisionKind,
    val instructions: String = "",
    val options: List<DecisionOption> = emptyList()
) {
    init {
        require(id.length in 1..60 && id.all { it.isLetterOrDigit() || it == '_' || it == '-' })
        require(instructions.length <= 8192)
        require(options.all { it.name.length <= 200 && it.description.length <= 8192 })
        when (kind) {
            DecisionKind.Choice -> {
                require(options.size in 1..2048) { "选择问题需要 1–2048 个选项" }
                require(options.all { it.name.isNotBlank() }) { "选项名称不能为空" }
                require(options.map { it.name }.distinct().size == options.size) { "选项名称不能重复" }
                require(options.none { it.name == DECISION_NONE_OPTION }) { "选项名称与保留项冲突" }
            }
            DecisionKind.Noul -> require(options.isEmpty() || options.size == 2)
            DecisionKind.Score -> require(options.size in 1..10) { "评分需要 1–10 个等级" }
        }
    }
}

data class DecisionBranchSpec(
    val question: DecisionQuestion,
    val suffix: String,
    val optionIndices: List<Int>,
    val chunk: Int? = null
)

data class DecisionRequestPlan(val state: String, val branches: List<DecisionBranchSpec>) {
    val suffixes get() = branches.map { it.suffix }
    val optionCounts get() = branches.map { it.optionIndices.size }
}

data class DecisionOptionResult(
    val name: String,
    val description: String,
    val probability: Double,
    val slot: String
)

data class DecisionAnswer(
    val questionId: String,
    val kind: DecisionKind,
    val options: List<DecisionOptionResult>,
    val bestIndex: Int,
    val score: Double? = null,
    val confidence: Double? = null,
    val allowedMass: Double,
    val chunks: Int
) {
    val yesProbability: Double? get() = if (kind == DecisionKind.Noul) options[0].probability else null
}

data class DecisionResult(
    val answers: List<DecisionAnswer>,
    val prefixTokens: Int,
    val prefixPrefillUs: Long,
    val branchPrefillUs: Long
)

fun renderDecisionState(input: String, json: Boolean): String {
    require(input.isNotBlank()) { "请输入状态或 JSON" }
    require(input.toByteArray(Charsets.UTF_8).size <= 131072) { "状态文本不能超过 128 KiB" }
    if (!json) return input
    val value = JSONTokener(input).nextValue()
    return when (value) {
        is JSONObject -> value.toString(2)
        is JSONArray -> value.toString(2)
        is String -> value
        else -> throw IllegalArgumentException("JSON 状态需要是对象、数组或字符串")
    }
}

private fun renderEntry(value: Any?): String = when (value) {
    null, JSONObject.NULL -> ""
    is String -> value.trim()
    is JSONObject -> value.toString(2)
    is JSONArray -> value.toString(2)
    is Number, is Boolean -> value.toString()
    else -> throw IllegalArgumentException("问题说明格式无效")
}

fun parseDecisionRequest(input: String): Pair<String, List<DecisionQuestion>> {
    val root = JSONTokener(input).nextValue() as? JSONObject
        ?: throw IllegalArgumentException("请求需要是 JSON 对象")
    val state = when (val value = root.opt("state")) {
        is String -> value
        is JSONObject -> value.toString(2)
        is JSONArray -> value.toString(2)
        null, JSONObject.NULL -> ""
        else -> value.toString()
    }
    val questionsJson = root.optJSONObject("questions")
        ?: throw IllegalArgumentException("缺少 questions 对象")
    require(questionsJson.length() in 1..32) { "每次需要 1–32 个问题" }
    val questions = questionsJson.keys().asSequence().map { id ->
        val item = questionsJson.getJSONObject(id)
        val kind = DecisionKind.entries.firstOrNull { it.json == item.optString("type") }
            ?: throw IllegalArgumentException("不支持的问题类型")
        val instructions = renderEntry(item.opt("instructions"))
        val options = when (kind) {
            DecisionKind.Choice -> {
                val criteria = item.optJSONObject("criteria")
                    ?: throw IllegalArgumentException("选择问题缺少 criteria 对象")
                require(criteria.length() in 1..2048) { "选择问题需要 1–2048 个选项" }
                criteria.keys().asSequence().map { name ->
                    DecisionOption(name, renderEntry(criteria.opt(name)))
                }.toList()
            }
            DecisionKind.Noul -> {
                val criteria = item.optJSONObject("criteria")
                if (criteria == null) emptyList() else listOf(
                    DecisionOption("yes", renderEntry(criteria.opt("true"))),
                    DecisionOption("no", renderEntry(criteria.opt("false")))
                )
            }
            DecisionKind.Score -> {
                val criteria = item.optJSONArray("criteria")
                    ?: throw IllegalArgumentException("评分问题缺少 criteria 数组")
                require(criteria.length() in 1..10) { "评分需要 1–10 个等级" }
                (0 until criteria.length()).map { index ->
                    DecisionOption(description = renderEntry(criteria.opt(index)))
                }
            }
        }
        DecisionQuestion(id, kind, instructions, options)
    }.toList()
    return state to questions
}

private fun renderSuffix(question: DecisionQuestion, names: List<String>, descriptions: List<String>): String {
    val instructions = question.instructions.trim().ifEmpty {
        when (question.kind) {
            DecisionKind.Choice -> "Which option applies to the state?"
            DecisionKind.Noul -> "Is the statement true, or is the answer to the question yes?"
            DecisionKind.Score -> "Which level describes the state?"
        }
    }
    val header = if (question.kind == DecisionKind.Score) "Rate the state:" else "Question:"
    val labels = decisionSlotLabels(names.size)
    val options = names.indices.joinToString("\n") { index ->
        val description = descriptions[index]
        when {
            question.kind == DecisionKind.Noul -> "${labels[index]}. ${names[index]}: $description"
            description.isNotEmpty() -> "${labels[index]}. ${names[index]}: $description"
            else -> "${labels[index]}. ${names[index]}"
        }
    }
    val answer = if (names.size <= 26) {
        "Answer with one letter: ${labels.joinToString(", ")}."
    } else {
        "Answer with one label: ${labels.joinToString(", ")}."
    }
    return "$header $instructions\n\nOptions:\n$options\n\n$answer"
}

private fun branch(question: DecisionQuestion, indices: List<Int>, chunk: Int? = null): DecisionBranchSpec {
    val options = when (question.kind) {
        DecisionKind.Noul -> if (question.options.isEmpty()) {
            listOf(DecisionOption("yes", NOUL_TRUE), DecisionOption("no", NOUL_FALSE))
        } else {
            question.options.mapIndexed { index, option ->
                DecisionOption(if (index == 0) "yes" else "no", option.description.ifEmpty {
                    if (index == 0) NOUL_TRUE else NOUL_FALSE
                })
            }
        }
        DecisionKind.Choice -> question.options
        DecisionKind.Score -> question.options.mapIndexed { index, option ->
            DecisionOption("level $index", option.description)
        }
    }
    val selected = indices.filter { it >= 0 }.map { options[it] } +
        if (indices.contains(-1)) listOf(DecisionOption(DECISION_NONE_OPTION, DECISION_NONE_DESCRIPTION)) else emptyList()
    return DecisionBranchSpec(question, renderSuffix(question, selected.map { it.name }, selected.map { it.description }),
        indices, chunk)
}

fun planDecisionRequest(state: String, questions: List<DecisionQuestion>, maxSlots: Int = 256): DecisionRequestPlan {
    require(state.isNotBlank()) { "状态不能为空" }
    require(state.toByteArray(Charsets.UTF_8).size <= 131072) { "状态文本不能超过 128 KiB" }
    require(questions.size in 1..32) { "每次需要 1–32 个问题" }
    require(maxSlots in 2..256)
    require(questions.map { it.id }.distinct().size == questions.size) { "问题 ID 不能重复" }
    val branches = questions.flatMap { question ->
        if (question.kind != DecisionKind.Choice || question.options.size <= maxSlots) {
            listOf(branch(question, question.options.indices.toList().ifEmpty { listOf(0, 1) }))
        } else {
            val count = ceil(question.options.size.toDouble() / (maxSlots - 1)).toInt()
            val size = ceil(question.options.size.toDouble() / count).toInt()
            question.options.indices.chunked(size).mapIndexed { index, chunk -> branch(question, chunk + -1, index) }
        }
    }
    require(branches.all { it.suffix.toByteArray(Charsets.UTF_8).size <= 131072 }) { "单个决策问题不能超过 128 KiB" }
    return DecisionRequestPlan(state, branches)
}

fun choiceConfidence(probabilities: List<Double>): Double {
    if (probabilities.size == 1) return 1.0
    val uniform = 1.0 / probabilities.size
    return ((probabilities.max() - uniform) / (1.0 - uniform)).coerceIn(0.0, 1.0)
}

fun expectedScore(probabilities: List<Double>): Double = probabilities.mapIndexed { index, p -> index * p }.sum()

fun scoreConfidence(probabilities: List<Double>): Double {
    if (probabilities.size == 1) return 1.0
    var mode = 0
    for (index in 1 until probabilities.size) {
        if (probabilities[index] > probabilities[mode]) mode = index
    }
    var distance = 0.0
    for (index in probabilities.indices) distance += probabilities[index] * kotlin.math.abs(index - mode)
    val center = (probabilities.size - 1) / 2.0
    val uniformDeviation = probabilities.indices.sumOf { kotlin.math.abs(it - center) } / probabilities.size
    return (1.0 - distance / uniformDeviation).coerceIn(0.0, 1.0)
}

private fun normalized(values: List<Double>): List<Double> {
    require(values.isNotEmpty() && values.all { it.isFinite() && it >= 0.0 })
    val total = values.sum()
    require(total.isFinite() && total > 0.0) { "模型返回了无效概率" }
    return values.map { it / total }
}

private data class EvaluatedBranch(
    val spec: DecisionBranchSpec,
    val probabilities: List<Double>,
    val allowedMass: Double,
    val prefillUs: Long
)

fun parseDecisionResult(json: String, plan: DecisionRequestPlan): DecisionResult {
    val root = JSONObject(json)
    val branches = root.getJSONArray("branches")
    require(branches.length() == plan.branches.size) { "决策结果数量不完整" }
    require(root.getInt("prefixTokens") > 0 && root.getLong("prefixPrefillUs") >= 0)
    val raw = plan.branches.mapIndexed { index, spec ->
        val branch = branches.getJSONObject(index)
        val probabilities = normalized(branch.getJSONArray("probabilities").let { values ->
            (0 until values.length()).map { index ->
                val value = values[index]
                require(value is Number && value.toDouble().isFinite()) { "模型返回了非数值概率" }
                value.toDouble()
            }
        })
        val allowedMass = branch.getDouble("allowedMass")
        require(allowedMass.isFinite() && allowedMass > 0.0 && allowedMass <= 1.000001)
        require(probabilities.size == spec.optionIndices.size)
        require(branch.getJSONArray("logits").length() == probabilities.size)
        require(branch.getInt("suffixTokens") > 0 && branch.getLong("prefillUs") >= 0)
        EvaluatedBranch(spec, probabilities, allowedMass, branch.getLong("prefillUs"))
    }
    val answers = plan.branches.map { it.question }.distinctBy { it.id }.map { question ->
        val own = raw.filter { it.spec.question.id == question.id }
        val slots = decisionSlotLabels()
        val probabilities: List<Double>
        val chunks: Int
        if (question.kind == DecisionKind.Choice && own.any { it.spec.chunk != null }) {
            val totalWeight = own.sumOf { (spec, values) ->
                val none = values[spec.optionIndices.indexOf(-1)]
                (1.0 - none).coerceAtLeast(1e-9)
            }.let { if (it == 0.0) 1.0 else it }
            probabilities = question.options.indices.map { option ->
                own.sumOf { (spec, values) ->
                    val none = values[spec.optionIndices.indexOf(-1)]
                    val keep = (1.0 - none).coerceAtLeast(1e-9)
                    val position = spec.optionIndices.indexOf(option)
                    if (position < 0) 0.0 else values[position] / keep * (keep / totalWeight)
                }
            }
            chunks = own.size
        } else {
            probabilities = own[0].probabilities
            chunks = 1
        }
        val normalizedProbabilities = normalized(probabilities)
        val names = when (question.kind) {
            DecisionKind.Noul -> listOf("yes", "no")
            DecisionKind.Choice -> question.options.map { it.name }
            DecisionKind.Score -> question.options.indices.map { "level $it" }
        }
        val descriptions = when (question.kind) {
            DecisionKind.Noul -> own[0].spec.optionIndices.map { index ->
                question.options.getOrNull(index)?.description?.ifEmpty {
                    if (index == 0) NOUL_TRUE else NOUL_FALSE
                } ?: if (index == 0) NOUL_TRUE else NOUL_FALSE
            }
            DecisionKind.Choice -> question.options.map { it.description }
            DecisionKind.Score -> question.options.map { it.description }
        }
        val optionSlots = names.indices.map { option ->
            val spec = own.first { it.spec.optionIndices.contains(option) }.spec
            spec.optionIndices.indexOf(option)
        }
        val options = names.indices.map { index ->
            DecisionOptionResult(names[index], descriptions[index], normalizedProbabilities[index], slots[optionSlots[index]])
        }
        DecisionAnswer(
            question.id, question.kind, options,
            bestIndex = normalizedProbabilities.indices.maxBy { normalizedProbabilities[it] },
            score = if (question.kind == DecisionKind.Score) expectedScore(normalizedProbabilities) else null,
            confidence = when (question.kind) {
                DecisionKind.Choice -> choiceConfidence(normalizedProbabilities)
                DecisionKind.Score -> scoreConfidence(normalizedProbabilities)
                DecisionKind.Noul -> null
            },
            allowedMass = own.map { it.allowedMass }.average(), chunks = chunks
        )
    }
    return DecisionResult(answers, root.getInt("prefixTokens"), root.getLong("prefixPrefillUs"),
        raw.sumOf { it.prefillUs })
}

package dev.techlab.android.gecko

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val BODY_FONT_SIZE_DP = 20f

/** Demo-owned types. Original source codes are mapped by the importer, never by the renderer. */
internal enum class QuestionType(val title: String) {
    SINGLE_CHOICE("单项选择"),
    MULTIPLE_CHOICE("多项选择"),
    FILL_IN("填空"),
    WORD_SPELLING("单词拼写"),
    MENTAL_ARITHMETIC("口算"),
    CALCULATION("计算"),
    WORKED_SOLUTION("解答"),
    READING("阅读理解"),
    CLOZE("完形填空"),
    WORD_BANK("选词填空"),
    CONTEXT_FILL("课文语法填空"),
    PASSAGE_COMPLETION("选句填空"),
    WRITING("写作"),
}

internal class QuestionCatalog(context: Context) {
    class Group(val type: QuestionType, val questions: List<JSONObject>)

    private val resources = context.resources
    private val sourceGroups = JSONArray(context.assets.open("gecko/catalog.json").bufferedReader().use { it.readText() })
        .objects().map { Group(QuestionType.valueOf(it.getString("questionType")), it.items("questions")) }
    private val byParent = sourceGroups.flatMap { it.questions }.filter { it.has("parentId") }
        .groupBy { it.getString("parentId") }
    val groups = sourceGroups.map { group ->
        Group(group.type, group.questions.filterNot { it.has("parentId") })
    }

    fun request(
        token: String,
        group: Group,
        background: Boolean,
        indices: Iterable<Int> = group.questions.indices,
        fontSizeDp: Float = BODY_FONT_SIZE_DP,
    ): JSONObject = JSONObject()
        .put("token", token)
        .put("presentation", if (background) "batch" else "list")
        .put("fontSizePx", (fontSizeDp * resources.displayMetrics.density).toDouble())
        .put("questions", JSONArray(indices.map { index ->
            renderEntry(group.questions[index], group.type, (index + 1).toString())
        }))

    private fun renderEntry(source: JSONObject, type: QuestionType, label: String): JSONObject = JSONObject().apply {
        val id = source.getString("id")
        val child = source.has("parentId")
        put("id", id)
        put("label", label)
        if (child && type == QuestionType.PASSAGE_COMPLETION) {
            put("answerLine", true)
            return@apply
        }
        for (key in listOf("html", "tex", "options", "answerInMaterial")) {
            if (source.has(key)) put(key, source.get(key))
        }
        val materialChoice = child && source.has("options") &&
            (type == QuestionType.CLOZE || type == QuestionType.READING)
        if (materialChoice || type == QuestionType.SINGLE_CHOICE || type == QuestionType.MULTIPLE_CHOICE) {
            put("choiceAnswer", true)
        }

        // The catalog stays flat. Only independently rendered children become nested cards.
        source.optJSONArray("inlineAnswers")?.let { refs ->
            put("inlineAnswers", refs)
            return@apply
        }
        val parts = byParent[id] ?: return@apply
        val numbered = type == QuestionType.CLOZE || type == QuestionType.PASSAGE_COMPLETION
        if (numbered) put("materialBlanks", JSONArray(parts.map { it.getString("id") }))
        put("parts", JSONArray(parts.mapIndexed { index, part ->
            val number = (index + 1).toString()
            renderEntry(part, type, if (numbered) number else "$label.$number")
        }))
    }
}

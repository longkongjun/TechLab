package dev.techlab.android.gecko.ui

import android.os.Bundle
import dev.techlab.android.gecko.QuestionType

/** Only small selections and completed numeric timings survive process death. */
internal fun RenderScreenState.saveTo(out: Bundle) = with(out) {
    putString("mode", mode.name)
    putString("questionType", question?.name)
    putBoolean("newInstanceOnRetest", fresh)
    putInt("layerMask", layers)
    Mode.entries.forEach { engine ->
        completedTiming(engine)?.let { time ->
            putBundle("timing.${engine.name}", Bundle().apply {
                time.preparation?.let { putLong("preparation", it) }
                time.first?.let { putLong("first", it) }
                time.completed?.let { putLong("completed", it) }
                putString("note", time.note)
            })
        } ?: legacyHistory[engine]?.let { putString("renderTiming.${engine.name}", it) }
    }
}

internal fun Bundle.restoreRenderScreen(): RenderScreenState {
    val oldLayers = getBooleanArray("layers")?.foldIndexed(0) { i, mask, enabled ->
        if (enabled) mask or (1 shl i) else mask
    } ?: 0
    val history = Mode.entries.mapNotNull { engine ->
        getBundle("timing.${engine.name}")?.let { data ->
            fun time(key: String) = if (data.containsKey(key)) data.getLong(key) else null
            engine to RenderTiming(time("preparation"), time("first"), time("completed"), data.getString("note").orEmpty())
        }
    }.toMap()
    val legacy = Mode.entries.mapNotNull { engine ->
        getString("renderTiming.${engine.name}")?.substringBefore('\n')?.let { engine to it }
    }.toMap()
    return RenderScreenState(
        mode = Mode.entries.firstOrNull { it.name == getString("mode") } ?: Mode.GECKO,
        question = QuestionType.entries.firstOrNull { it.name == getString("questionType") },
        fresh = getBoolean("newInstanceOnRetest"), layers = getInt("layerMask", oldLayers),
        history = history, legacyHistory = legacy,
    )
}

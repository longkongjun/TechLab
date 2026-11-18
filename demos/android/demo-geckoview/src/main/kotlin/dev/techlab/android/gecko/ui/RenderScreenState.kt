package dev.techlab.android.gecko.ui

import dev.techlab.android.gecko.QuestionType
import dev.techlab.android.gecko.producer.FirstFrameStages
import dev.techlab.android.gecko.producer.ProducerEvent
internal enum class Mode(val title: String) { GECKO("GeckoView"), WEBVIEW("WebView"), PRODUCER("后台产图") }

internal data class TypeOption(val type: QuestionType, val count: Int) {
    val label get() = "${type.title}（$count）"
}

internal data class RenderTiming(val preparation: Long?, val first: Long?, val completed: Long?, val note: String = "") {
    fun format(mode: Mode, status: String = note): String {
        fun ms(value: Long?) = value?.let { "$it ms" } ?: "—"
        val times = "${mode.title} · 准备 ${ms(preparation)} · 首屏 ${ms(first)} · 完成 ${ms(completed)}"
        return if (status.isEmpty()) times else "$times · $status"
    }
}

internal data class RenderRun(
    val token: String,
    val requestedAt: Long,
    val actionAt: Long = requestedAt,
    val startedAt: Long = requestedAt,
    val inputAt: Long? = null,
    val firstMs: Long? = null,
    val finishedMs: Long? = null,
    val warning: String = "",
    val error: String? = null,
) {
    val running get() = finishedMs == null && error == null

    fun geometry(now: Long, settled: Boolean, warning: String): RenderRun {
        if (!running) return this
        val elapsed = now - startedAt
        return copy(firstMs = firstMs ?: elapsed, finishedMs = elapsed.takeIf { settled }, warning = warning)
    }
}

internal data class BatchProgress(
    val total: Int,
    val preRenderMs: Long = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val phase: String = "页面",
    val stages: FirstFrameStages? = null,
    val rows: List<ProducerEvent.Item> = emptyList(),
)

internal data class RenderScreenState(
    val mode: Mode = Mode.GECKO,
    val options: List<TypeOption> = emptyList(),
    val question: QuestionType? = null,
    val fresh: Boolean = false,
    val layers: Int = 0,
    val runs: Map<Mode, RenderRun> = emptyMap(),
    // A present entry with no duration identifies a mounted document awaiting its bridge.
    val preparation: Map<Mode, Long?> = emptyMap(),
    val errors: Map<Mode, String> = emptyMap(),
    val history: Map<Mode, RenderTiming> = emptyMap(),
    val legacyHistory: Map<Mode, String> = emptyMap(),
    val connected: Boolean = false,
    val batch: BatchProgress? = null,
) {
    val selected get() = options.firstOrNull { it.type == question }
    val canRetest get() = selected != null && runs[mode]?.running != true && when (mode) {
        Mode.PRODUCER -> connected
        else -> mode !in preparation || preparation[mode] != null
    }

    fun completedTiming(engine: Mode): RenderTiming? = runs[engine]?.takeIf { it.finishedMs != null }?.let {
        RenderTiming(preparation[engine], it.firstMs, it.finishedMs, it.warning)
    } ?: history[engine]

    fun timingLine(engine: Mode, now: Long): String {
        val run = runs[engine]
        val prepared = preparation[engine]
        val error = errors[engine] ?: run?.error
        if (run == null && engine !in preparation && error == null) {
            val last = history[engine]?.format(engine) ?: legacyHistory[engine]
            if (last != null) return "$last · 上次"
        }
        val status = when {
            error != null -> error
            prepared == null && (engine in preparation || run?.running == true) -> "准备中"
            run?.running == true -> {
                val phase = if (engine == Mode.PRODUCER && run.firstMs == null) batch?.phase ?: "页面" else "处理中"
                "$phase ${now - run.startedAt} ms"
            }
            else -> run?.warning.orEmpty()
        }
        return RenderTiming(prepared, run?.firstMs, run?.finishedMs).format(engine, status)
    }
}

internal sealed interface RenderAction {
    data class SelectMode(val mode: Mode) : RenderAction
    data class SelectType(val type: QuestionType) : RenderAction
    data class SetLayer(val index: Int, val checked: Boolean) : RenderAction
    data class SetFresh(val checked: Boolean) : RenderAction
    data object Retest : RenderAction
}

/** Synchronous instructions for the currently attached host. Never replayed by state collection. */
internal sealed interface RenderWork {
    data class Cancel(val mode: Mode) : RenderWork
    data class Load(val fresh: Boolean = false) : RenderWork
}

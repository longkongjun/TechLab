package dev.techlab.android.handwriting

import androidx.ink.brush.Brush
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.Stroke

internal enum class BoardPage { BLANK, QUESTION }
internal enum class Tool { PEN, HIGHLIGHTER, ERASER, REGION_ERASER, TRAIL_ERASER }

internal data class PenStyle(val tool: Tool, val color: Int, val size: Float) {
    fun brush(): Brush {
        val family = when (tool) {
            Tool.PEN -> StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1)
            Tool.HIGHLIGHTER -> StockBrushes.highlighter(
                SelfOverlap.DISCARD, StockBrushes.HighlighterVersion.V1
            )
            Tool.ERASER, Tool.REGION_ERASER, Tool.TRAIL_ERASER -> error("An eraser has no ink")
        }
        val argb = if (tool == Tool.HIGHLIGHTER) (color and 0xffffff) or 0x66000000 else color
        return Brush.createWithColorIntArgb(family, argb, size, 0.12f)
    }
}

internal data class Mark(val style: PenStyle, val stroke: Stroke, val clip: InkClip? = null)

// Snapshots share immutable strokes. Restoring a snapshot also restores the exact z-order.
internal data class History<T>(
    val visible: List<T> = emptyList(),
    val past: List<List<T>> = emptyList(),
    val future: List<List<T>> = emptyList(),
) {
    fun edit(next: List<T>): History<T> =
        if (next == visible) this else History(next, (past + listOf(visible)).takeLast(50))

    fun undo() = past.lastOrNull()?.let { History(it, past.dropLast(1), future + listOf(visible)) } ?: this
    fun redo() = future.lastOrNull()?.let { History(it, past + listOf(visible), future.dropLast(1)) } ?: this
}

internal data class BoardState(
    val ready: Boolean = false,
    val page: BoardPage = BoardPage.BLANK,
    val pages: Map<BoardPage, History<Mark>> = emptyMap(),
    val tool: Tool = Tool.PEN,
    val pen: PenStyle = PenStyle(Tool.PEN, 0xff243448.toInt(), 4f),
    val highlighter: PenStyle = PenStyle(Tool.HIGHLIGHTER, 0xffffc928.toInt(), 26f),
    val fingerWrites: Boolean = false,
    val exporting: Boolean = false,
    val error: String? = null,
) {
    val history get() = pages[page] ?: History()
    val style get() = if (tool == Tool.HIGHLIGHTER) highlighter else pen
}

internal sealed interface BoardIntent {
    data class Open(val page: BoardPage) : BoardIntent
    data class Select(val tool: Tool) : BoardIntent
    data class Style(val style: PenStyle) : BoardIntent
    data class Finger(val enabled: Boolean) : BoardIntent
    data class Draw(val page: BoardPage, val mark: Mark) : BoardIntent
    data class Erase(val page: BoardPage, val replacements: Map<Mark, Mark?>) : BoardIntent
    data class Export(val background: Boolean) : BoardIntent
    data object Undo : BoardIntent
    data object Redo : BoardIntent
}

internal fun BoardState.reduce(intent: BoardIntent): BoardState {
    fun edit(target: BoardPage, update: (History<Mark>) -> History<Mark>): BoardState {
        val previous = pages[target] ?: History()
        val next = update(previous)
        return if (next === previous) this else copy(pages = pages + (target to next))
    }
    return when (intent) {
        is BoardIntent.Open -> copy(page = intent.page)
        is BoardIntent.Select -> copy(tool = intent.tool)
        is BoardIntent.Style -> when (intent.style.tool) {
            Tool.PEN -> copy(pen = intent.style)
            Tool.HIGHLIGHTER -> copy(highlighter = intent.style)
            Tool.ERASER, Tool.REGION_ERASER, Tool.TRAIL_ERASER -> this
        }
        is BoardIntent.Finger -> copy(fingerWrites = intent.enabled)
        is BoardIntent.Export -> copy(exporting = true)
        is BoardIntent.Draw -> edit(intent.page) { it.edit(it.visible + intent.mark) }
        is BoardIntent.Erase -> edit(intent.page) { history ->
            history.edit(history.visible.mapNotNull { mark ->
                if (mark in intent.replacements) intent.replacements[mark] else mark
            })
        }
        BoardIntent.Undo -> edit(page) { it.undo() }
        BoardIntent.Redo -> edit(page) { it.redo() }
    }
}

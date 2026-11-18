package dev.techlab.android.handwriting

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.withSave
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableParallelogram
import androidx.ink.geometry.ImmutableSegment
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.Intersection.intersects
import androidx.ink.geometry.outlinesToPath
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import kotlin.math.atan2
import kotlin.math.hypot

internal sealed interface EraseArea {
    fun footprint(): Path
}

/** One continuous sweep between two input samples, in page coordinates. */
internal data class EraserSweep(val from: ImmutableVec, val to: ImmutableVec, val radius: Float) : EraseArea {
    override fun footprint() = footprint(round = true)

    fun footprint(round: Boolean): Path {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val end = hypot(dx, dy) + radius
        return Path().apply {
            if (round) addRoundRect(-radius, -radius, end, radius, radius, radius, Path.Direction.CW)
            else addRect(-radius, -radius, end, radius, Path.Direction.CW)
            transform(Matrix().apply {
                setRotate(Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat())
                postTranslate(from.x, from.y)
            })
        }
    }
}

internal data class EnclosedArea(val vertices: List<ImmutableVec>) : EraseArea {
    override fun footprint() = Path().apply {
        fillType = Path.FillType.EVEN_ODD
        vertices.forEachIndexed { index, point ->
            if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
        }
        close()
    }
}

// Both stock brushes on this board have one render group.
private fun Stroke.outline() = shape.outlinesToPath(0)

/** Published paths are read-only. A new erase gesture works on its own copy. */
internal class InkClip(val areas: List<EraseArea>, val remaining: Path) {
    companion object {
        fun restore(stroke: Stroke, areas: List<EraseArea>): InkClip? {
            if (areas.isEmpty()) return null
            val remaining = stroke.outline()
            areas.forEach { check(remaining.op(it.footprint(), Path.Op.DIFFERENCE)) }
            return InkClip(areas, remaining)
        }
    }
}

internal fun CanvasStrokeRenderer.drawMark(
    canvas: Canvas, mark: Mark, transform: Matrix, clip: Path? = mark.clip?.remaining,
) {
    if (clip == null) draw(canvas, mark.stroke, transform)
    else canvas.withSave {
        clipPath(clip)
        draw(this, mark.stroke, transform)
    }
}

internal class BoardEraser {
    private class Cut(val remaining: Path, val areas: MutableList<EraseArea> = mutableListOf())
    private val cuts = mutableMapOf<Mark, Cut>()
    private val removed = mutableSetOf<Mark>()
    private val intersection = Path()

    fun isRemoved(mark: Mark) = mark in removed
    fun clipFor(mark: Mark) = cuts[mark]?.remaining ?: mark.clip?.remaining

    fun sweep(marks: List<Mark>, sweep: EraserSweep, wholeStroke: Boolean) {
        val bounds = ImmutableParallelogram.fromSegmentAndPadding(ImmutableSegment(sweep.from, sweep.to), sweep.radius)
        val footprint by lazy(LazyThreadSafetyMode.NONE) { sweep.footprint(round = !wholeStroke) }
        for (mark in marks) {
            if (mark in removed || !mark.stroke.shape.intersects(bounds, AffineTransform.IDENTITY)) continue
            if (wholeStroke) {
                if (mark.clip == null || touches(mark.clip.remaining, footprint)) removed += mark
            } else {
                cut(mark, sweep, footprint)
            }
        }
    }

    fun enclose(marks: List<Mark>, area: EnclosedArea) {
        if (area.vertices.size < 3) return
        val footprint = area.footprint()
        val bounds = RectF()
        footprint.computeBounds(bounds, true)
        if (bounds.isEmpty) return
        for (mark in marks) {
            val ink = mark.stroke.shape.computeBoundingBox() ?: continue
            if (bounds.intersects(ink.xMin, ink.yMin, ink.xMax, ink.yMax)) cut(mark, area, footprint)
        }
    }

    private fun touches(visible: Path, footprint: Path) =
        intersection.op(visible, footprint, Path.Op.INTERSECT) && !intersection.isEmpty

    private fun cut(mark: Mark, area: EraseArea, footprint: Path) {
        val visible = clipFor(mark) ?: mark.stroke.outline()
        if (!touches(visible, footprint)) return
        val cut = cuts.getOrPut(mark) { Cut(Path(visible)) }
        if (!cut.remaining.op(footprint, Path.Op.DIFFERENCE)) return
        cut.areas += area
        if (cut.remaining.isEmpty) {
            removed += mark
            cuts.remove(mark)
        }
    }

    fun replacements(): Map<Mark, Mark?> = buildMap {
        removed.forEach { put(it, null) }
        cuts.forEach { (mark, cut) ->
            if (cut.areas.isNotEmpty()) {
                put(mark, mark.copy(clip = InkClip(mark.clip?.areas.orEmpty() + cut.areas, cut.remaining)))
            }
        }
    }
}

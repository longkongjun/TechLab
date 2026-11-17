package dev.techlab.android.gecko

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import org.json.JSONArray
import org.json.JSONObject

internal class GeometryOverlay(
    context: Context,
    private val visibility: BooleanArray,
    private val pageToView: (Matrix) -> Boolean,
) : View(context) {
    private var rectangles = GeometryRectangles(null)
    private val pageToSurface = Matrix()

    fun display(geometry: JSONArray?) {
        rectangles = GeometryRectangles(geometry)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        if (!pageToView(pageToSurface)) return
        rectangles.draw(canvas, pageToSurface, visibility, width, height)
    }
}

internal class GeometryRectangles(geometry: JSONArray?) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val mapped = RectF()
    private val endpoints = FloatArray(4)
    private data class Segment(val left: Float, val right: Float, val y: Float)
    private val baselines = mutableListOf<Segment>()
    private val colors = intArrayOf(0xff1761d4.toInt(), 0xff147c52.toInt(), 0xffc33d24.toInt())
    private val groups = Array(3) { mutableListOf<RectF>() }

    init {
        fun add(group: Int, data: JSONObject) {
            groups[group].add(data.getJSONObject("rect").toRect())
        }
        geometry?.objects()?.forEach { question ->
            add(0, question)
            question.items("lines").forEach { line ->
                add(1, line)
                val baseline = line.optDouble("baseLine")
                if (baseline.isFinite()) {
                    val rect = groups[1].last()
                    baselines.add(Segment(rect.left, rect.right, baseline.toFloat()))
                }
            }
            question.items("checks").forEach { add(2, it) }
        }
    }

    fun draw(canvas: Canvas, matrix: Matrix, visibility: BooleanArray, width: Int, height: Int) {
        for (group in groups.indices) {
            if (!visibility[group]) continue
            paint.color = colors[group]
            for (box in groups[group]) {
                matrix.mapRect(mapped, box)
                if (mapped.right > 0 && mapped.bottom > 0 && mapped.left < width && mapped.top < height) {
                    canvas.drawRect(mapped, paint)
                }
            }
        }
        if (visibility[3]) {
            paint.color = 0xff7631ac.toInt()
            for (line in baselines) {
                endpoints[0] = line.left
                endpoints[1] = line.y
                endpoints[2] = line.right
                endpoints[3] = line.y
                matrix.mapPoints(endpoints)
                if (endpoints[1] in 0f..height.toFloat() && endpoints[2] > 0 && endpoints[0] < width) {
                    canvas.drawLine(endpoints[0], endpoints[1], endpoints[2], endpoints[3], paint)
                }
            }
        }
    }
}

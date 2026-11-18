package dev.techlab.android.handwriting

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import androidx.ink.geometry.ImmutableVec
import kotlin.math.min

internal class BoardViewport {
    val matrix = Matrix()
    private val page = RectF(0f, 0f, BoardPaper.WIDTH.toFloat(), BoardPaper.HEIGHT.toFloat())
    val visiblePage = RectF()
    var scale = 1f
        private set
    private var fit = 1f
    private var width = 0
    private var height = 0

    fun reset(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        width = w
        height = h
        fit = min(w / page.width(), h / page.height()) * 0.96f
        place(fit, (w - page.width() * fit) / 2, (h - page.height() * fit) / 2)
    }

    fun navigate(oldX: Float, oldY: Float, x: Float, y: Float, ratio: Float) {
        val anchor = point(oldX, oldY)
        val next = (scale * ratio).coerceIn(fit, fit * 8f)
        place(next, x - anchor.x * next, y - anchor.y * next)
    }

    private fun place(next: Float, left: Float, top: Float) {
        fun offset(value: Float, view: Int, paper: Float): Float =
            if (paper < view) (view - paper) / 2 else value.coerceIn(view - paper, 0f)
        scale = next
        matrix.setScale(next, next)
        matrix.postTranslate(offset(left, width, page.width() * next), offset(top, height, page.height() * next))
        matrix.mapRect(visiblePage, page)
    }

    fun point(x: Float, y: Float) =
        ImmutableVec((x - visiblePage.left) / scale, (y - visiblePage.top) / scale)

    fun outsideMask() = Path().apply {
        // Ink clears this path on its front buffer. Only submit the actual margins.
        val right = width.toFloat()
        val bottom = height.toFloat()
        val paper = RectF(visiblePage)
        if (!paper.intersect(0f, 0f, right, bottom)) {
            addRect(0f, 0f, right, bottom, Path.Direction.CW)
            return@apply
        }
        if (paper.top > 0f) addRect(0f, 0f, right, paper.top, Path.Direction.CW)
        if (paper.bottom < bottom) addRect(0f, paper.bottom, right, bottom, Path.Direction.CW)
        if (paper.left > 0f) addRect(0f, paper.top, paper.left, paper.bottom, Path.Direction.CW)
        if (paper.right < right) addRect(paper.right, paper.top, right, paper.bottom, Path.Direction.CW)
    }
}

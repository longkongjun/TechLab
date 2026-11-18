package dev.techlab.android.handwriting

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import java.io.IOException
import java.io.OutputStream

internal object BoardPaper {
    const val WIDTH = 1000
    const val HEIGHT = 1200

    fun background(canvas: Canvas, page: BoardPage) = with(canvas) {
        drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply { color = Color.WHITE })
        if (page == BoardPage.BLANK) return@with
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff203b47.toInt() }
        ink.typeface = Typeface.DEFAULT_BOLD
        ink.textSize = 44f
        drawText("直角三角形的面积", 72f, 120f, ink)
        ink.typeface = Typeface.DEFAULT
        ink.textSize = 29f
        drawText("在图中标出底和高，写出三角形 ABC 的面积。", 72f, 200f, ink)
        ink.strokeWidth = 4f
        drawLine(190f, 495f, 190f, 285f, ink)
        drawLine(190f, 285f, 540f, 495f, ink)
        drawLine(540f, 495f, 190f, 495f, ink)
        drawLine(190f, 470f, 215f, 470f, ink)
        drawLine(215f, 470f, 215f, 495f, ink)
        drawText("A", 151f, 517f, ink)
        drawText("B", 155f, 283f, ink)
        drawText("C", 551f, 510f, ink)
        drawText("6 cm", 95f, 392f, ink)
        drawText("10 cm", 335f, 541f, ink)
        drawText("解答", 72f, 660f, ink)
        ink.color = 0xffd2dfe2.toInt()
        ink.strokeWidth = 2f
        for (y in 750..1050 step 100) drawLine(72f, y.toFloat(), 928f, y.toFloat(), ink)
    }

    fun writePng(snapshot: ExportPage, output: OutputStream) {
        val bitmap = createBitmap(WIDTH, HEIGHT)
        try {
            val canvas = Canvas(bitmap)
            if (snapshot.background) background(canvas, snapshot.page)
            val renderer = CanvasStrokeRenderer.create()
            val identity = Matrix()
            snapshot.marks.forEach { renderer.drawMark(canvas, it, identity) }
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("PNG 编码失败")
        } finally {
            bitmap.recycle()
        }
    }
}

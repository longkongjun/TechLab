package dev.techlab.android.handwriting

import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import androidx.core.view.drawToBitmap
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.ImmutableVec
import androidx.ink.storage.encode
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class BoardTrailEraseTest {
    private fun line(style: PenStyle, y: Float, left: Float = 100f, right: Float = 900f): Mark {
        val inputs = MutableStrokeInputBatch()
        listOf(left, right).forEachIndexed { index, x ->
            inputs.add(StrokeInput().apply { update(x, y, index * 100L, InputToolType.STYLUS, pressure = 0.8f) })
        }
        return Mark(style, Stroke(style.brush(), inputs))
    }

    @Test fun partialErasePreviewsCancelsAndRoundTripsFromLegacyDraft() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val dir = Files.createTempDirectory(app.cacheDir.toPath(), "area-erase").toFile()
        val files = BoardDrafts(object : ContextWrapper(app) { override fun getFilesDir() = dir })
        try {
            val pen = line(BoardState().pen, 750f)
            val highlight = line(BoardState().highlighter, 780f)
            writeLegacyDraft(dir, listOf(pen, highlight))
            val draft = files.load()
            val originals = draft.sheets.getValue(BoardPage.QUESTION)
            ActivityScenario.launch(HandwritingActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val view = activity.findViewById<BoardCanvas>(R.id.canvas)
                    var state = BoardState(ready = true, page = draft.page, fingerWrites = true,
                        pages = draft.sheets.mapValues { History(it.value) }, tool = Tool.TRAIL_ERASER)
                    fun send(intent: BoardIntent) { state = state.reduce(intent); view.render(state) }
                    view.dispatch = ::send
                    view.render(state)
                    val viewport = BoardViewport().apply { reset(view.width, view.height) }
                    fun x(doc: Int) = viewport.visiblePage.left + doc * viewport.scale
                    fun y(doc: Int) = viewport.visiblePage.top + doc * viewport.scale
                    var start = 0L
                    fun touch(action: Int, docX: Int, docY: Int) {
                        val now = SystemClock.uptimeMillis()
                        if (action == MotionEvent.ACTION_DOWN) start = now
                        val event = MotionEvent.obtain(start, now, action, x(docX), y(docY), 0)
                        try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
                    }
                    fun pixel(docX: Int, docY: Int) = view.drawToBitmap().let {
                        try { it.getPixel(x(docX).roundToInt(), y(docY).roundToInt()) } finally { it.recycle() }
                    }
                    fun cross() {
                        touch(MotionEvent.ACTION_DOWN, 450, 670)
                        touch(MotionEvent.ACTION_MOVE, 450, 850)
                    }

                    val before = pixel(450, 780)
                    cross()
                    assertEquals(Color.WHITE, pixel(450, 780))
                    assertTrue(state.history.past.isEmpty())
                    touch(MotionEvent.ACTION_CANCEL, 450, 850)
                    assertEquals(before, pixel(450, 780))
                    cross()
                    touch(MotionEvent.ACTION_UP, 450, 850)
                    val cut = state.history.visible
                    assertEquals(listOf(Tool.PEN, Tool.HIGHLIGHTER), cut.map { it.style.tool })
                    assertTrue(cut.all { it.clip != null })
                    assertEquals(1, state.history.past.size)
                    send(BoardIntent.Undo)
                    assertEquals(originals, state.history.visible)
                    send(BoardIntent.Redo)
                    assertEquals(cut, state.history.visible)
                    send(BoardIntent.Open(BoardPage.BLANK))
                    assertTrue(state.history.visible.isEmpty())
                    send(BoardIntent.Open(BoardPage.QUESTION))
                    files.save(BoardDraft(state.page, state.pages.mapValues { it.value.visible }))
                    val reopened = files.load().sheets.getValue(BoardPage.QUESTION)
                    val ink = png(reopened, background = false)
                    val page = png(reopened, background = true)
                    val paper = png(emptyList(), background = true)
                    try {
                        for (row in listOf(750, 780)) {
                            assertEquals(0, Color.alpha(ink.getPixel(450, row)))
                            assertEquals(paper.getPixel(450, row), page.getPixel(450, row))
                            for (col in listOf(250, 650)) assertTrue(Color.alpha(ink.getPixel(col, row)) > 0)
                        }
                        assertTrue(Color.alpha(ink.getPixel(250, 780)) in 1..254)
                    } finally { ink.recycle(); page.recycle(); paper.recycle() }

                    val whole = BoardEraser()
                    val gap = ImmutableVec(450f, 750f)
                    whole.sweep(reopened, EraserSweep(gap, gap, 2f), wholeStroke = true)
                    assertTrue(whole.replacements().isEmpty())
                    val end = ImmutableVec(250f, 750f)
                    whole.sweep(reopened, EraserSweep(end, end, 2f), wholeStroke = true)
                    assertEquals(mapOf(reopened.first() to null), whole.replacements())
                    val fresh = line(pen.style, 750f)
                    val overGap = png(reopened + fresh, background = false)
                    try { assertTrue(Color.alpha(overGap.getPixel(450, 750)) > 0) } finally { overGap.recycle() }
                }
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun erasingEdgeOfWideHighlighterKeepsItsCenterAndTapCanRemoveEntireMark() {
        val mark = line(BoardState().highlighter.copy(size = 40f), 400f)
        val eraser = BoardEraser()
        val before = png(listOf(mark), background = false)
        try {
            val edge = (350 until 400).first { Color.alpha(before.getPixel(500, it)) > 64 }
            val point = ImmutableVec(500f, edge.toFloat())
            eraser.sweep(listOf(mark), EraserSweep(point, point, 4f), wholeStroke = false)
            val after = png(listOf(checkNotNull(eraser.replacements()[mark])), background = false)
            try {
                assertEquals(0, Color.alpha(after.getPixel(500, edge)))
                assertEquals(before.getPixel(500, 400), after.getPixel(500, 400))
            } finally { after.recycle() }
        } finally { before.recycle() }
        val short = line(BoardState().pen, 400f, left = 500f, right = 501f)
        val tap = ImmutableVec(500f, 400f)
        val wide = BoardEraser()
        wide.sweep(listOf(short), EraserSweep(tap, tap, 15f), wholeStroke = false)
        assertEquals(mapOf(short to null), wide.replacements())
    }

    private fun png(marks: List<Mark>, background: Boolean) = ByteArrayOutputStream().let { out ->
        BoardPaper.writePng(ExportPage(BoardPage.QUESTION, marks, background), out)
        BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    }

    private fun writeLegacyDraft(dir: File, marks: List<Mark>) {
        DataOutputStream(File(dir, "handwriting-v1.board").outputStream()).use { out ->
            out.writeInt(1)
            out.writeUTF(BoardPage.QUESTION.name)
            out.writeInt(0) // blank page
            out.writeInt(marks.size)
            marks.forEach { (style, stroke) ->
                out.writeUTF(style.tool.name)
                out.writeInt(style.color)
                out.writeFloat(style.size)
                val bytes = ByteArrayOutputStream().also(stroke.inputs::encode)
                out.writeInt(bytes.size())
                bytes.writeTo(out)
            }
        }
    }
}

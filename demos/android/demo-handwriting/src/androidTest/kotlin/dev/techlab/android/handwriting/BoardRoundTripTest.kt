package dev.techlab.android.handwriting

import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class BoardRoundTripTest {
    @Test fun penUpImmediatePageSwitchEraseUndoDraftAndBothExports() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val dir = Files.createTempDirectory(app.cacheDir.toPath(), "ink-test").toFile()
        val files = BoardDrafts(object : ContextWrapper(app) {
            override fun getFilesDir(): File = dir
        })
        try {
            ActivityScenario.launch(HandwritingActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val canvas = activity.findViewById<BoardCanvas>(R.id.canvas)
                    var state = BoardState(ready = true, page = BoardPage.QUESTION)
                    fun send(intent: BoardIntent) {
                        state = state.reduce(intent)
                        canvas.render(state)
                    }
                    canvas.dispatch = ::send
                    canvas.render(state)
                    val viewport = BoardViewport().apply { reset(canvas.width, canvas.height) }
                    fun trace(tool: Int, x1: Int, y1: Int, x2: Int, y2: Int) {
                        val start = SystemClock.uptimeMillis()
                        val actions = listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)
                        actions.forEachIndexed { index, action ->
                            val t = index / 2f
                            val p = MotionEvent.PointerProperties().apply { id = 4; toolType = tool }
                            val c = MotionEvent.PointerCoords().apply {
                                x = viewport.visiblePage.left + (x1 + t * (x2 - x1)) * viewport.scale
                                y = viewport.visiblePage.top + (y1 + t * (y2 - y1)) * viewport.scale
                                pressure = 0.6f
                            }
                            val event = MotionEvent.obtain(start, start + index * 12, action, 1, arrayOf(p),
                                arrayOf(c), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
                            try { canvas.dispatchTouchEvent(event) } finally { event.recycle() }
                        }
                    }
                    trace(MotionEvent.TOOL_TYPE_FINGER, 100, 700, 300, 700)
                    assertTrue(state.history.visible.isEmpty())
                    val cx = canvas.width / 2f
                    val cy = canvas.height / 2f
                    pinch(canvas, cx, cy)
                    viewport.navigate(cx, cy, cx, cy, 1.5f)
                    trace(MotionEvent.TOOL_TYPE_STYLUS, 350, 700, 550, 700)
                    send(BoardIntent.Select(Tool.HIGHLIGHTER))
                    trace(MotionEvent.TOOL_TYPE_STYLUS, 350, 730, 550, 730)
                    val drawn = state.history.visible
                    assertEquals(2, drawn.size)
                    send(BoardIntent.Open(BoardPage.BLANK))
                    assertTrue(state.history.visible.isEmpty())
                    send(BoardIntent.Open(BoardPage.QUESTION))
                    viewport.reset(canvas.width, canvas.height)
                    assertEquals(drawn, state.history.visible)
                    trace(MotionEvent.TOOL_TYPE_ERASER, 450, 670, 450, 780)
                    assertTrue(state.history.visible.isEmpty())
                    send(BoardIntent.Undo)
                    assertEquals(drawn, state.history.visible)
                    send(BoardIntent.Redo)
                    assertTrue(state.history.visible.isEmpty())
                    send(BoardIntent.Undo)

                    files.save(BoardDraft(state.page, state.pages.mapValues { it.value.visible }))
                    val restored = files.load()
                    val marks = restored.sheets.getValue(BoardPage.QUESTION)
                    assertEquals(BoardPage.QUESTION, restored.page)
                    assertEquals(drawn.map { it.style }, marks.map { it.style })
                    fun png(background: Boolean) = ByteArrayOutputStream().let { out ->
                        BoardPaper.writePng(ExportPage(restored.page, marks, background), out)
                        BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
                    }
                    val ink = png(false)
                    val page = png(true)
                    assertEquals(BoardPaper.WIDTH, ink.width)
                    assertEquals(BoardPaper.HEIGHT, ink.height)
                    assertEquals(0, Color.alpha(ink.getPixel(5, 5)))
                    assertEquals(Color.WHITE, page.getPixel(5, 5))
                    assertTrue(Color.alpha(ink.getPixel(450, 700)) > 0)
                    assertTrue(Color.alpha(ink.getPixel(450, 730)) in 1..254)
                    assertNotEquals(Color.WHITE, page.getPixel(450, 700))
                    assertNotEquals(Color.WHITE, page.getPixel(190, 400))
                    ink.recycle()
                    page.recycle()
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun pinch(view: BoardCanvas, cx: Float, cy: Float) {
        val start = SystemClock.uptimeMillis()
        fun event(action: Int, vararg positions: Float) {
            val properties = Array(positions.size) { i ->
                MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER }
            }
            val coords = Array(positions.size) { i ->
                MotionEvent.PointerCoords().apply { x = positions[i]; y = cy; pressure = 1f }
            }
            val motion = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, coords.size,
                properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { view.dispatchTouchEvent(motion) } finally { motion.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, cx - 100)
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), cx - 100, cx + 100)
        event(MotionEvent.ACTION_MOVE, cx - 150, cx + 150)
        event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), cx - 150, cx + 150)
        event(MotionEvent.ACTION_UP, cx - 150)
    }
}

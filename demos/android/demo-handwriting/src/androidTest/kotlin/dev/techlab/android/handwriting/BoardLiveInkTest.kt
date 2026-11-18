package dev.techlab.android.handwriting

import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class BoardLiveInkTest {
    @Test fun strokeExtendsOnScreenBeforePenUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(HandwritingActivity::class.java).use { scenario ->
            lateinit var canvas: BoardCanvas
            val viewport = BoardViewport()
            val origin = IntArray(2)
            var state = BoardState(ready = true)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                canvas = activity.findViewById(R.id.canvas)
                canvas.dispatch = { intent ->
                    state = state.reduce(intent)
                    canvas.render(state)
                }
                canvas.render(state)
                viewport.reset(canvas.width, canvas.height)
                canvas.getLocationOnScreen(origin)
            }
            fun localX(x: Int) = viewport.visiblePage.left + x * viewport.scale
            val y = viewport.visiblePage.top + 600 * viewport.scale
            val start = SystemClock.uptimeMillis()
            fun sample(action: Int, x: Int) = scenario.onActivity {
                val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
                val point = MotionEvent.PointerCoords().apply {
                    this.x = localX(x)
                    this.y = y
                    pressure = 0.8f
                }
                val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, 1, arrayOf(pointer),
                    arrayOf(point), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
                try { assertTrue(canvas.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
            // UiAutomation captures the composed screen, including Ink's separate SurfaceView.
            fun hasInk(x: Int): Boolean {
                val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                val cx = origin[0] + localX(x).roundToInt()
                val cy = origin[1] + y.roundToInt()
                return try {
                    (-2..2).any { dy -> Color.red(frame.getPixel(cx, cy + dy)) < 140 }
                } finally { frame.recycle() }
            }
            fun awaitInk(x: Int): Boolean {
                val deadline = SystemClock.uptimeMillis() + 1500
                do {
                    if (hasInk(x)) return true
                    SystemClock.sleep(32)
                } while (SystemClock.uptimeMillis() < deadline)
                return false
            }
            fun extend(range: IntProgression) = range.forEach { x ->
                SystemClock.sleep(16)
                sample(MotionEvent.ACTION_MOVE, x)
            }

            assertFalse(hasInk(320))
            sample(MotionEvent.ACTION_DOWN, 200)
            extend(240..480 step 40)
            val firstSegment = awaitInk(320)
            assertFalse(hasInk(680))
            extend(520..840 step 40)
            val secondSegment = awaitInk(680)
            extend(880..1000 step 40)
            sample(MotionEvent.ACTION_MOVE, 1014)
            val edgeSegment = awaitInk(990)
            assertFalse("Live ink leaked into the page margin", hasInk(1008))
            scenario.onActivity { assertTrue(state.history.visible.isEmpty()) }
            sample(MotionEvent.ACTION_UP, 1014)
            scenario.onActivity { assertEquals(1, state.history.visible.size) }
            assertTrue("Completed stroke missing", awaitInk(680))
            assertTrue("Ink before UP: first=$firstSegment second=$secondSegment edge=$edgeSegment",
                firstSegment && secondSegment && edgeSegment)
        }
    }
}

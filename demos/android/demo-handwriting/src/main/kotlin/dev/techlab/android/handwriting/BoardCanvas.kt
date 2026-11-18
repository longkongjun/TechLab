package dev.techlab.android.handwriting

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.core.graphics.withSave
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.ImmutableVec
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import kotlin.math.hypot

/** Only transient pointer, viewport and wet/dry handoff state lives in the View. */
internal class BoardCanvas(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    var dispatch: (BoardIntent) -> Unit = {}
    private var board = BoardState()
    private val viewport = BoardViewport()
    private val renderer = CanvasStrokeRenderer.create()
    private val eraserRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
    }
    private val selectionFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x302d8092 }
    private val waiting = mutableMapOf<InProgressStrokeId, Mark>()
    private var wet = newWetLayer()
    private var gesture: Gesture? = null
    private var navigating = false
    private var previousFocus: Focus? = null

    private sealed interface Gesture {
        val pointer: Int
        val page: BoardPage
        val pen: Boolean
        data class Draw(
            override val pointer: Int, override val page: BoardPage,
            val id: InProgressStrokeId, val started: Long, override val pen: Boolean,
            val style: PenStyle, val brush: Brush, val samples: MutableStrokeInputBatch,
            var last: StrokeInput,
        ) : Gesture
        data class Erase(
            override val pointer: Int, override val page: BoardPage,
            override val pen: Boolean, var last: ImmutableVec, val wholeStroke: Boolean,
            val radius: Float, val eraser: BoardEraser = BoardEraser(),
        ) : Gesture
        class Enclose(override val pointer: Int, override val page: BoardPage, override val pen: Boolean) : Gesture {
            val points = mutableListOf<ImmutableVec>()
            val outline = Path().apply { fillType = Path.FillType.EVEN_ODD }

            fun append(point: ImmutableVec) {
                if (point == points.lastOrNull()) return
                if (points.isEmpty()) outline.moveTo(point.x, point.y) else outline.lineTo(point.x, point.y)
                points += point
            }
        }
    }

    private data class Focus(val x: Float, val y: Float, val span: Float)

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.rgb(226, 233, 234))
        addView(wet)
    }

    private fun newWetLayer() = InProgressStrokesView(context).apply {
        val layer = this
        addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                strokes.keys.forEach(waiting::remove)
                layer.removeFinishedStrokes(strokes.keys)
                this@BoardCanvas.invalidate()
            }
        })
    }

    fun render(next: BoardState) {
        if (board.page != next.page) {
            cancelGesture()
            viewport.reset(width, height)
            refreshViewport()
        } else if (waiting.values.any { it !in next.history.visible }) {
            retireWetLayer()
        }
        board = next
        invalidate()
    }

    private fun retireWetLayer() {
        if (waiting.isEmpty()) return
        // The old surface may still show handed-up strokes at the previous pan/zoom.
        if (gesture is Gesture.Draw) cancelGesture()
        removeView(wet)
        waiting.clear()
        wet = newWetLayer()
        addView(wet)
        wet.maskPath = viewport.outsideMask()
    }

    private fun refreshViewport() {
        retireWetLayer()
        wet.maskPath = viewport.outsideMask()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cancelGesture()
        viewport.reset(w, h)
        refreshViewport()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.withSave {
            concat(viewport.matrix)
            clipRect(0, 0, BoardPaper.WIDTH, BoardPaper.HEIGHT)
            BoardPaper.background(this, board.page)
            val active = gesture as? Gesture.Erase
            val eraser = active?.eraser
            board.history.visible.forEach { mark ->
                if (eraser?.isRemoved(mark) != true && mark !in waiting.values) {
                    renderer.drawMark(this, mark, viewport.matrix, eraser?.clipFor(mark) ?: mark.clip?.remaining)
                }
            }
            eraserRing.strokeWidth = resources.displayMetrics.density / viewport.scale
            if (active != null && !active.wholeStroke) {
                drawCircle(active.last.x, active.last.y, active.radius, eraserRing)
            }
            (gesture as? Gesture.Enclose)?.let { region ->
                drawPath(region.outline, selectionFill)
                drawPath(region.outline, eraserRing)
                val first = region.points.first()
                val last = region.points.last()
                drawLine(last.x, last.y, first.x, first.y, eraserRing)
            }
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!board.ready) return false
        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.isPen(index)) {
                    if (gesture?.pen != true) {
                        cancelGesture()
                        navigating = false
                        start(event, index)
                    }
                } else if (gesture?.pen != true) {
                    if (event.pointerCount == 1 && board.fingerWrites) start(event, index)
                    else {
                        cancelGesture()
                        navigating = true
                        previousFocus = focus(event)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val active = gesture
                if (active != null) {
                    val i = event.findPointerIndex(active.pointer)
                    if (i >= 0) move(active, event, i)
                } else if (navigating && (!board.fingerWrites || event.pointerCount >= 2)) {
                    val now = focus(event)
                    previousFocus?.let { old ->
                        val ratio = if (old.span > 0 && now.span > 0) now.span / old.span else 1f
                        viewport.navigate(old.x, old.y, now.x, now.y, ratio)
                        refreshViewport()
                    }
                    previousFocus = now
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                gesture?.takeIf { it.pointer == event.getPointerId(index) }?.let { active ->
                    if (event.flags and MotionEvent.FLAG_CANCELED != 0) cancelGesture()
                    else finish(active, event, index)
                }
                previousFocus = null
                if (event.actionMasked == MotionEvent.ACTION_UP) navigating = false
            }
            MotionEvent.ACTION_CANCEL -> cancelGesture()
        }
        return true
    }

    private fun MotionEvent.isPen(i: Int) =
        getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS || getToolType(i) == MotionEvent.TOOL_TYPE_ERASER

    private fun focus(event: MotionEvent): Focus {
        if (event.pointerCount == 1) return Focus(event.x, event.y, 0f)
        val x2 = event.getX(1)
        val y2 = event.getY(1)
        return Focus((event.x + x2) / 2, (event.y + y2) / 2, hypot(event.x - x2, event.y - y2))
    }

    private fun start(event: MotionEvent, i: Int) {
        if (!viewport.visiblePage.contains(event.getX(i), event.getY(i))) return
        val pen = event.isPen(i)
        val pointer = event.getPointerId(i)
        requestUnbufferedDispatch(event)
        if (board.tool == Tool.REGION_ERASER) {
            retireWetLayer()
            gesture = Gesture.Enclose(pointer, board.page, pen).apply {
                append(viewport.point(event.getX(i), event.getY(i)))
            }
            invalidate()
        } else if (board.tool == Tool.TRAIL_ERASER || board.tool == Tool.ERASER ||
            event.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER) {
            retireWetLayer()
            val point = viewport.point(event.getX(i), event.getY(i))
            val radius = 12f * resources.displayMetrics.density / viewport.scale
            gesture = Gesture.Erase(pointer, board.page, pen, point, board.tool != Tool.TRAIL_ERASER, radius)
                .also { erase(it, point) }
            invalidate()
        } else {
            val input = sample(event, i, -1, event.eventTime, pen)
            val brush = board.style.brush()
            val id = wet.startStroke(input, brush, viewport.matrix)
            gesture = Gesture.Draw(pointer, board.page, id, event.eventTime, pen, board.style,
                brush, MutableStrokeInputBatch().add(input), input)
        }
    }

    private fun sample(event: MotionEvent, i: Int, history: Int, start: Long, pen: Boolean): StrokeInput {
        val historical = history >= 0
        val time = if (historical) event.getHistoricalEventTime(history) else event.eventTime
        val x = if (historical) event.getHistoricalX(i, history) else event.getX(i)
        val y = if (historical) event.getHistoricalY(i, history) else event.getY(i)
        val pressure = if (historical) event.getHistoricalPressure(i, history) else event.getPressure(i)
        val point = viewport.point(x, y)
        return StrokeInput().apply {
            update(point.x, point.y, (time - start).coerceAtLeast(0),
                if (pen) InputToolType.STYLUS else InputToolType.TOUCH,
                pressure = if (pen) pressure.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
                    else StrokeInput.NO_PRESSURE)
        }
    }

    private fun move(active: Gesture, event: MotionEvent, i: Int) {
        when (active) {
            is Gesture.Draw -> {
                val batch = MutableStrokeInputBatch()
                for (h in 0..event.historySize) {
                    val input = sample(event, i, if (h < event.historySize) h else -1, active.started, active.pen)
                    val last = active.last
                    if (input.elapsedTimeMillis < last.elapsedTimeMillis ||
                        (input.elapsedTimeMillis == last.elapsedTimeMillis && input.x == last.x && input.y == last.y)) continue
                    active.samples.add(input)
                    active.last = input
                    batch.add(input)
                }
                if (!batch.isEmpty()) wet.addToStroke(batch, active.id)
            }
            is Gesture.Erase -> event.forEachPagePoint(i) { erase(active, it) }
            is Gesture.Enclose -> event.forEachPagePoint(i, active::append)
        }
    }

    private inline fun MotionEvent.forEachPagePoint(i: Int, consume: (ImmutableVec) -> Unit) {
        for (h in 0 until historySize) consume(viewport.point(getHistoricalX(i, h), getHistoricalY(i, h)))
        consume(viewport.point(getX(i), getY(i)))
        invalidate()
    }

    private fun erase(active: Gesture.Erase, point: ImmutableVec) {
        active.eraser.sweep(board.history.visible, EraserSweep(active.last, point, active.radius), active.wholeStroke)
        active.last = point
    }

    private fun finish(active: Gesture, event: MotionEvent, i: Int) {
        move(active, event, i)
        gesture = null
        when (active) {
            is Gesture.Draw -> {
                // Persist on real UP, independently of the SDK's deferred visual handoff.
                val mark = Mark(active.style, Stroke(active.brush, active.samples))
                waiting[active.id] = mark
                wet.finishStroke(active.last, active.id)
                dispatch(BoardIntent.Draw(active.page, mark))
            }
            is Gesture.Erase -> dispatch(BoardIntent.Erase(active.page, active.eraser.replacements()))
            is Gesture.Enclose -> {
                val edit = BoardEraser()
                edit.enclose(board.history.visible, EnclosedArea(active.points.toList()))
                dispatch(BoardIntent.Erase(active.page, edit.replacements()))
            }
        }
        invalidate()
    }

    fun cancelGesture() {
        (gesture as? Gesture.Draw)?.let { wet.cancelStroke(it.id) }
        gesture = null
        previousFocus = null
        navigating = false
        invalidate()
    }
}

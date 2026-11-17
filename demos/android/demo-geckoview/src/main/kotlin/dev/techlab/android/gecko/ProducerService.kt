package dev.techlab.android.gecko

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ImageReader
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoDisplay
import org.mozilla.geckoview.GeckoSession
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

class ProducerService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val commands = Messenger(Handler(Looper.getMainLooper(), ::receive))
    private val writer = Executors.newSingleThreadExecutor()
    private var job: Job? = null

    private data class Ready(val index: Int, val questionId: String, val layout: JSONObject, val readyAt: Long)

    private class Batch(
        val client: Messenger,
        val renderer: PageRenderer,
        val group: QuestionCatalog.Group,
        indices: IntRange,
        val width: Int,
        val started: Long,
        val coldRuntime: Boolean,
        root: File
    ) {
        val total = indices.count()
        val pending = indices.associateBy { group.questions[it].getString("id") }.toMutableMap()
        val directory = File(root, UUID.randomUUID().toString())
        var recovered = false
        var startupReported = false
        var engineStartedAt = 0L
        var renderStartedAt = 0L
        var succeeded = 0
        var failed = 0
        var first = -1L
    }

    private class Job(val batch: Batch) {
        val id: String = UUID.randomUUID().toString()
        // A stale writer from the old session must only clean up its own files.
        val directory = if (batch.recovered) File(batch.directory, "retry") else batch.directory
        val indices = batch.pending.values.toList()
        val session = GeckoSession()
        val waiting = batch.pending.toMutableMap()
        val ready = ArrayDeque<Ready>()
        var display: GeckoDisplay? = null
        var reader: ImageReader? = null
        var timeout: Runnable? = null
        var inFlight: Ready? = null
        var inputAt = 0L
    }

    override fun onBind(intent: Intent) = commands.binder

    private fun receive(message: Message): Boolean {
        val client = message.replyTo ?: return false
        if (message.what == CANCEL) {
            job?.takeIf { it.batch.client.binder == client.binder }?.let { finish(it, true) }
            return true
        }
        if (message.what != START) return false
        if (job != null) {
            error(client, "另一个批次正在运行")
            return true
        }

        val startTime = SystemClock.elapsedRealtime()
        val coldRuntime = !PageRenderer.isInitialized
        val args = message.data
        try {
            val renderer = PageRenderer.get(this, "producer")
            val type = args.getString("questionType")
            val group = renderer.catalog.groups.firstOrNull { it.type.name == type }
            val available = group?.questions?.size ?: 0
            val start = args.getInt("startIndex")
            val limit = args.getInt("limit")
            val width = args.getInt("width", DEFAULT_WIDTH)
            if (group == null || start !in 0 until available || limit < 0 || width !in 1..MAX_RENDER_WIDTH) {
                error(client, "无效题型、题目范围或输出宽度")
                return true
            }
            val end = if (limit == 0) available else start + min(limit, available - start)
            startAttempt(Batch(client, renderer, group, start until end, width, startTime, coldRuntime,
                File(cacheDir, "rendered")))
        } catch (failure: Exception) {
            error(client, failure.toString())
        }
        return true
    }

    private fun startAttempt(batch: Batch) {
        if (batch.engineStartedAt == 0L) batch.engineStartedAt = SystemClock.elapsedRealtime()
        val run = Job(batch)
        job = run
        try {
            check(run.directory.mkdirs()) { "无法建立产物目录" }
            run.session.contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onCrash(session: GeckoSession) {
                    main.post { recover(run, "Gecko 内容进程崩溃") }
                }

                override fun onKill(session: GeckoSession) {
                    main.post { recover(run, "Gecko 内容进程被终止") }
                }
            }
            val renderer = batch.renderer
            run.session.open(renderer.runtime)
            val display = run.session.acquireDisplay().also { run.display = it }
            val reader = ImageReader.newInstance(batch.width, HEIGHT, ImageFormat.PRIVATE, 2).also { run.reader = it }
            reader.setOnImageAvailableListener({ source ->
                // Free surface buffers. The screenshot is obtained through GeckoDisplay.
                try {
                    source.acquireLatestImage()?.close()
                } catch (failure: RuntimeException) {
                    abort(run, "离屏 Surface 缓冲失败：${failure.message}")
                }
            }, main)
            display.surfaceChanged(GeckoDisplay.SurfaceInfo.Builder(reader.surface).size(batch.width, HEIGHT).build())
            run.session.setActive(true)
            armTimeout(run) { abort(run, "渲染实例准备超时") }
            renderer.attach(run.session, { token ->
                if (job === run && token == run.id) {
                    run.inputAt = SystemClock.elapsedRealtime()
                    phase(run, "排版")
                    renderer.catalog.request(token, batch.group, background = true, indices = run.indices)
                } else null
            }, { onLayout(run, it) }, {
                if (job !== run) return@attach
                watchBatch(run)
                if (!batch.startupReported) {
                    batch.startupReported = true
                    batch.renderStartedAt = SystemClock.elapsedRealtime()
                    reply(batch.client, STARTED, Bundle().apply {
                        putInt("total", batch.total)
                        putString("directory", batch.directory.absolutePath)
                        putInt("rendererPid", Process.myPid())
                        putBoolean("coldRuntime", batch.coldRuntime)
                        putLong("startedAt", batch.started)
                        putLong("startupMs", batch.renderStartedAt - batch.engineStartedAt)
                        putLong("renderStartedAt", batch.renderStartedAt)
                        renderer.runtimeCreationMs?.let { putLong("runtimeCreationMs", it) }
                    })
                }
                run.session.loadUri(renderer.url(run.id))
            }, { abort(run, it.toString()) })
        } catch (failure: Exception) {
            abort(run, failure.toString())
        }
    }

    private fun recover(run: Job, reason: String) {
        if (job !== run) return
        val batch = run.batch
        if (batch.recovered) {
            abort(run, reason)
            return
        }
        Log.w(TAG, "$reason · 恢复 ${batch.pending.size} 道未完成题目")
        batch.recovered = true
        release(run)
        startAttempt(batch)
    }

    private fun active(run: Job, item: Ready) = job === run && run.inFlight === item

    private fun onLayout(run: Job, data: JSONObject) {
        if (job !== run || data.optString("token") != run.id) return
        if (data.optString("type") == "error") {
            abort(run, data.optString("message"))
            return
        }
        if (data.optString("type") != "question-ready") return
        val question = data.optString("questionId")
        val index = run.waiting.remove(question) ?: return
        val error = data.optString("error")
        when {
            error.isNotEmpty() -> reportFailure(run, index, question, error)
            data.optString("coordinateSpace") != "document-css-pixels" ||
                data.optJSONArray("geometry")?.optJSONObject(0)?.optString("questionId") != question ->
                reportFailure(run, index, question, "几何输出与当前题目不匹配")
            else -> run.ready.addLast(Ready(index, question, data, SystemClock.elapsedRealtime()))
        }
        watchBatch(run)
        captureNext(run)
    }

    private fun captureNext(run: Job) {
        if (job !== run || run.inFlight != null) return
        val item = run.ready.removeFirstOrNull()
        if (item == null) {
            if (run.waiting.isEmpty()) finish(run, false)
            return
        }
        // Bound bitmap memory while image decoding and layout continue for the other cards.
        run.inFlight = item
        phase(run, "截图")
        capture(run, item, 0)
    }

    private fun capture(run: Job, item: Ready, retries: Int) {
        if (!active(run, item)) return
        try {
            val geometry = item.layout.getJSONArray("geometry")
            val bounds = geometry.getJSONObject(0).getJSONObject("rect").toRect()
            val scale = item.layout.getDouble("pixelRatio").toFloat()
            check(scale.isFinite() && scale > 0f && bounds.left >= 0f && bounds.top >= 0f && !bounds.isEmpty)

            // Gecko converts source offsets and extents from device pixels to whole CSS pixels.
            // Round outward and use Gecko's quantized origin when mapping the returned geometry.
            val left = floor(bounds.left * scale).toInt()
            val top = floor(bounds.top * scale).toInt()
            val cssLeft = (left / scale).toInt()
            val cssTop = (top / scale).toInt()
            val width = ceil((ceil(bounds.right) - cssLeft) * scale).toInt()
            val height = ceil((ceil(bounds.bottom) - cssTop) * scale).toInt()
            val map = Matrix().apply {
                setScale(scale, scale)
                postTranslate(-cssLeft * scale, -cssTop * scale)
            }
            checkNotNull(run.display).screenshot().source(left, top, width, height).captureFullPage().accept({ bitmap ->
                if (bitmap != null) handleFrame(run, item, geometry, map, bitmap)
                else if (active(run, item)) failActive(run, item, "没有读到 Gecko 位图")
            }, { failure ->
                if (active(run, item)) {
                    if (retries < 8) main.postDelayed({ capture(run, item, retries + 1) }, 125)
                    else failActive(run, item, failure.toString())
                }
            })
        } catch (failure: Exception) {
            failActive(run, item, failure.toString())
        }
    }

    private fun handleFrame(run: Job, item: Ready, geometry: JSONArray, matrix: Matrix, bitmap: Bitmap) {
        if (!active(run, item)) {
            bitmap.recycle()
            return
        }
        val capturedAt = SystemClock.elapsedRealtime()
        phase(run, "写盘")
        try {
            val top = geometry.getJSONObject(0)
            val bounds = top.getJSONObject("rect").mapToSurface(matrix)
            val crop = Rect().apply { bounds.roundOut(this) }
            check(crop.left >= -1 && crop.top >= -1 && crop.right <= bitmap.width + 1 &&
                crop.bottom <= bitmap.height + 1 && crop.intersect(0, 0, bitmap.width, bitmap.height)) {
                "题目超出区域截图：$bounds / ${bitmap.width}×${bitmap.height}"
            }
            val metadata = JSONObject()
                .put("questionType", run.batch.group.type.name).put("questionId", item.questionId).put("questionIndex", item.index)
                .put("width", crop.width()).put("height", crop.height())
                .put("renderer", "pages")
                .put("formulaFallbacks", item.layout.optInt("formulaFallbacks"))
                .put("coordinateSpace", "image-pixels").put("geometry", geometry.toImageGeometry(matrix, crop))
            writer.execute { save(run, item, bitmap, crop, metadata, capturedAt) }
        } catch (failure: Exception) {
            bitmap.recycle()
            failActive(run, item, failure.toString())
        }
    }

    private fun save(run: Job, item: Ready, frame: Bitmap, crop: Rect, data: JSONObject, capturedAt: Long) {
        var bitmap = frame
        val stem = "%03d-%s".format(java.util.Locale.ROOT, item.index + 1, item.questionId)
        val png = File(run.directory, "$stem.png")
        val json = File(run.directory, "$stem.json")
        try {
            bitmap = Bitmap.createBitmap(frame, crop.left, crop.top, crop.width(), crop.height())
            png.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            json.writeText(data.toString(2))
            val result = Bundle().apply {
                putString("image", png.absolutePath)
                putString("metadata", json.absolutePath)
                putString("questionId", item.questionId)
                putInt("width", crop.width())
                putInt("height", crop.height())
                putInt("questionIndex", item.index)
            }
            main.post {
                if (active(run, item)) {
                    val batch = run.batch
                    run.inFlight = null
                    batch.pending.remove(item.questionId)
                    batch.succeeded++
                    result.putLong("rendererPssKb", Debug.getPss())
                    val time = SystemClock.elapsedRealtime() - batch.started
                    if (batch.first < 0) batch.first = time
                    result.putLong("elapsedMs", time)
                    result.putLong("questionMs", time)
                    // All segments use the same monotonic clock and add up to this image's render time.
                    result.putLong("pageMs", run.inputAt - batch.renderStartedAt)
                    result.putLong("layoutMs", item.readyAt - run.inputAt)
                    result.putLong("captureMs", capturedAt - item.readyAt)
                    result.putLong("writeMs", batch.started + time - capturedAt)
                    reply(batch.client, ITEM, result)
                    watchBatch(run)
                    captureNext(run)
                } else {
                    png.delete()
                    json.delete()
                }
            }
        } catch (failure: Exception) {
            png.delete()
            json.delete()
            main.post { if (active(run, item)) failActive(run, item, failure.toString()) }
        } finally {
            if (bitmap !== frame) bitmap.recycle()
            frame.recycle()
        }
    }

    private fun failActive(run: Job, item: Ready, reason: String) {
        run.inFlight = null
        reportFailure(run, item.index, item.questionId, reason)
        watchBatch(run)
        captureNext(run)
    }

    private fun reportFailure(run: Job, index: Int, question: String, reason: String) {
        val batch = run.batch
        if (batch.pending.remove(question) == null) return
        batch.failed++
        Log.w(TAG, "Question $index failed: $reason")
        val elapsed = SystemClock.elapsedRealtime() - batch.started
        reply(batch.client, ITEM_ERROR, Bundle().apply {
            putInt("questionIndex", index)
            putString("questionId", question)
            putLong("questionMs", elapsed)
            putLong("elapsedMs", elapsed)
            putString("error", reason)
        })
    }

    private fun watchBatch(run: Job) = armTimeout(run) {
        if (job !== run) return@armTimeout
        run.inFlight?.let { reportFailure(run, it.index, it.questionId, "裁图或写盘超时") }
        run.ready.forEach { reportFailure(run, it.index, it.questionId, "等待裁图超时") }
        run.waiting.forEach { (question, index) -> reportFailure(run, index, question, "等待题目就绪超时") }
        finish(run, false)
    }

    private fun armTimeout(run: Job, timeout: () -> Unit) {
        disarmTimeout(run)
        run.timeout = Runnable(timeout).also { main.postDelayed(it, 20_000) }
    }

    private fun disarmTimeout(run: Job) {
        run.timeout?.let(main::removeCallbacks)
        run.timeout = null
    }

    private fun finish(run: Job, cancelled: Boolean) {
        val batch = run.batch
        val elapsedMs = SystemClock.elapsedRealtime() - batch.started
        release(run)
        reply(batch.client, DONE, Bundle().apply {
            putInt("succeeded", batch.succeeded)
            putInt("failed", batch.failed)
            putBoolean("cancelled", cancelled)
            putLong("firstMs", batch.first)
            putLong("elapsedMs", elapsedMs)
        })
    }

    private fun abort(run: Job, reason: String) {
        if (job !== run) return
        release(run)
        error(run.batch.client, reason)
    }

    private fun release(run: Job) {
        job = null
        disarmTimeout(run)
        run.display?.let {
            if (run.session.isOpen) run.session.setActive(false)
            it.surfaceDestroyed()
            run.session.releaseDisplay(it)
        }
        run.reader?.setOnImageAvailableListener(null, null)
        run.reader?.close()
        if (run.session.isOpen) run.session.close()
    }

    private fun error(client: Messenger, reason: String) =
        reply(client, ERROR, Bundle().apply { putString("error", reason) })

    private fun phase(run: Job, name: String) {
        if (run.batch.first >= 0) return
        reply(run.batch.client, PHASE, Bundle().apply { putString("phase", name) })
    }

    private fun reply(client: Messenger, code: Int, payload: Bundle) {
        try {
            client.send(Message.obtain(null, code).apply { data = payload })
        } catch (failure: RemoteException) {
            Log.w(TAG, "Batch client disconnected", failure)
        }
    }

    override fun onDestroy() {
        job?.let(::release)
        writer.shutdown()
        super.onDestroy()
    }

    companion object {
        const val START = 1
        const val CANCEL = 2
        const val STARTED = 3
        const val ITEM = 4
        const val ITEM_ERROR = 5
        const val DONE = 6
        const val ERROR = 7
        const val PHASE = 10
        // The viewport defines each card's fixed-height document slot, including below the fold.
        internal const val MAX_RENDER_WIDTH = 1440
        private const val DEFAULT_WIDTH = 1000
        private const val HEIGHT = 3200
        private const val TAG = "TechLabProducer"
    }
}

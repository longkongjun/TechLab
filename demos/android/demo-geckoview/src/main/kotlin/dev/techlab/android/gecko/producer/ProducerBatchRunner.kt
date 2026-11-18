package dev.techlab.android.gecko.producer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import dev.techlab.android.gecko.ProducerService
import dev.techlab.android.gecko.QuestionCatalog
import dev.techlab.android.gecko.engine.GeckoPage
import dev.techlab.android.gecko.engine.PageRenderer
import dev.techlab.android.gecko.mapToSurface
import dev.techlab.android.gecko.toImageGeometry
import dev.techlab.android.gecko.toRect
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

internal class ProducerBatchRunner(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private var attempt: Attempt? = null
    private var offscreen: OffscreenPage? = null
    private val page get() = offscreen?.page

    private data class Ready(val index: Int, val questionId: String, val layout: JSONObject, val readyAt: Long)

    private class Batch(
        val client: Messenger,
        val renderer: PageRenderer,
        val group: QuestionCatalog.Group,
        indices: IntRange,
        val width: Int,
        val started: Long,
        val coldRuntime: Boolean,
        val deferInput: Boolean,
        val sampleMemory: Boolean,
        root: File
    ) {
        val total = indices.count()
        val pending = indices.associateBy { group.questions[it].getString("id") }.toMutableMap()
        val directory = File(root, UUID.randomUUID().toString())
        var recovered = false
        var startupReported = false
        var renderStartedAt = 0L
        var succeeded = 0
        var failed = 0
        var first = -1L
    }

    private class Attempt(val batch: Batch) {
        val id: String = UUID.randomUUID().toString()
        // A stale writer from the old session must only clean up its own files.
        val directory = if (batch.recovered) File(batch.directory, "retry") else batch.directory
        val indices = batch.pending.values.toList()
        val waiting = batch.pending.toMutableMap()
        val ready = ArrayDeque<Ready>()
        var timeout: Runnable? = null
        var inFlight: Ready? = null
        var inputAt = 0L
    }

    fun preload() {
        try {
            ensurePage(PageRenderer.producer(context))
        } catch (failure: Exception) {
            Log.w(TAG, "后台页面预加载失败", failure)
            closePage()
        }
    }

    fun cancel(client: Messenger) {
        attempt?.takeIf { it.batch.client.binder == client.binder }?.let { finish(it, cancelled = true) }
    }

    fun start(client: Messenger, request: BatchCommand.Start) {
        if (attempt != null) {
            reply(client, ProducerEvent.Error("另一个批次正在运行"))
            return
        }
        val startTime = SystemClock.elapsedRealtime()
        val coldRuntime = !PageRenderer.isInitialized
        try {
            val renderer = PageRenderer.producer(context)
            val group = renderer.catalog.groups.firstOrNull { it.type.name == request.questionType }
            val available = group?.questions?.size ?: 0
            val start = request.startIndex
            val limit = request.limit
            if (group == null || start !in 0 until available || limit < 0 || request.width !in 1..ProducerService.MAX_RENDER_WIDTH) {
                reply(client, ProducerEvent.Error("无效题型、题目范围或输出宽度"))
                return
            }
            val end = if (limit == 0) available else start + min(limit, available - start)
            startAttempt(Batch(client, renderer, group, start until end, request.width, startTime, coldRuntime,
                request.deferInput, request.sampleMemory, File(context.cacheDir, "rendered")))
        } catch (failure: Exception) {
            reply(client, ProducerEvent.Error(failure.toString()))
        }
    }

    private fun ensurePage(renderer: PageRenderer): GeckoPage {
        page?.let {
            if (it.document.reusable || it.canReload) return it
            closePage()
        }
        val resident = OffscreenPage.open(renderer, main)
        offscreen = resident
        val created = resident.page
        val doc = created.document
        doc.receive = { data -> attempt?.let { onLayout(it, data) } }
        doc.onState = {
            if (offscreen === resident) {
                val failure = doc.failure
                if (failure == null) {
                    attempt?.let(::sendQuestions)
                } else {
                    attempt?.batch?.let { batch ->
                        if (batch.renderStartedAt == 0L) batch.renderStartedAt = doc.renderStart(batch.started)
                    }
                    val incompatible = doc.incompatible
                    if (!created.canReload) closePage()
                    attempt?.let { if (incompatible) abort(it, failure) else recover(it, failure) }
                }
            }
        }
        return created
    }

    private fun startAttempt(batch: Batch) {
        val run = Attempt(batch)
        attempt = run
        try {
            check(run.directory.mkdirs()) { "无法建立产物目录" }
            val resident = ensurePage(batch.renderer)
            if (resident.canReload) resident.reload()
            val doc = resident.document
            armTimeout(run) { if (attempt === run) doc.fail("渲染页面准备超时") }
            sendQuestions(run)
        } catch (failure: Exception) {
            abort(run, failure.toString())
        }
    }

    private fun sendQuestions(run: Attempt) {
        val document = page?.document ?: return
        if (attempt !== run || run.inputAt != 0L || !document.isReady) return
        val batch = run.batch
        if (!batch.startupReported) {
            batch.startupReported = true
            if (batch.renderStartedAt == 0L) batch.renderStartedAt = document.renderStart(batch.started)
            reply(batch.client, ProducerEvent.Started(
                batch.total, batch.directory.absolutePath, Process.myPid(), batch.coldRuntime,
                batch.started, checkNotNull(document.preparationMs), document.navigationAt,
                batch.renderStartedAt, batch.renderer.runtimeCreationMs,
            ))
        }
        run.inputAt = SystemClock.elapsedRealtime()
        phase(run, "排版")
        watchBatch(run)
        try {
            val input = batch.renderer.catalog.request(run.id, batch.group, background = true, indices = run.indices)
                .put("widthPx", batch.width)
            document.setQuestions(input, batch.deferInput)
        } catch (error: Exception) {
            document.fail(error.toString())
        }
    }

    private fun recover(run: Attempt, reason: String) {
        if (attempt !== run) return
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

    private fun active(run: Attempt, item: Ready) = attempt === run && run.inFlight === item

    private fun onLayout(run: Attempt, data: JSONObject) {
        if (attempt !== run || data.optString("token") != run.id) return
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
            else -> {
                val readyAt = SystemClock.elapsedRealtime()
                run.ready.addLast(Ready(index, question, data, readyAt))
                data.optJSONObject("pageTiming")?.let {
                    val started = run.batch.renderStartedAt
                    Log.i("TechLabFirst", "PRODUCER token=${run.id} id=$question doc=${page?.document?.navigationAt} requestToInputMs=${run.inputAt - started} " +
                        "readyMs=${readyAt - started} page=$it")
                }
            }
        }
        watchBatch(run)
        captureNext(run)
    }

    private fun captureNext(run: Attempt) {
        if (attempt !== run || run.inFlight != null) return
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

    private fun capture(run: Attempt, item: Ready, retries: Int) {
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
            checkNotNull(offscreen).display.screenshot().source(left, top, width, height).captureFullPage().accept({ bitmap ->
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

    private fun handleFrame(run: Attempt, item: Ready, geometry: JSONArray, matrix: Matrix, bitmap: Bitmap) {
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
            writer.execute {
                val output = writeArtifacts(run.directory, item.index, item.questionId, bitmap, crop, metadata)
                main.post {
                    output.onSuccess { files ->
                        if (active(run, item)) onWritten(run, item, files, capturedAt)
                        else files.deleteArtifacts()
                    }.onFailure { if (active(run, item)) failActive(run, item, it.toString()) }
                }
            }
        } catch (failure: Exception) {
            bitmap.recycle()
            failActive(run, item, failure.toString())
        }
    }

    private fun onWritten(run: Attempt, item: Ready, files: OutputFiles, capturedAt: Long) {
        val batch = run.batch
        run.inFlight = null
        batch.pending.remove(item.questionId)
        batch.succeeded++
        val pss = if (batch.sampleMemory) Debug.getPss() else null
        val writtenAt = SystemClock.elapsedRealtime()
        val elapsed = writtenAt - batch.started
        if (batch.first < 0) batch.first = elapsed
        val stages = FirstFrameStages.measured(batch.renderStartedAt, run.inputAt, item.readyAt, capturedAt, writtenAt)
        reply(batch.client, ProducerEvent.Written(item.index, item.questionId, elapsed, run.id, files, stages, pss))
        watchBatch(run)
        captureNext(run)
    }

    private fun failActive(run: Attempt, item: Ready, reason: String) {
        if (!active(run, item)) return
        run.inFlight = null
        reportFailure(run, item.index, item.questionId, reason)
        watchBatch(run)
        captureNext(run)
    }

    private fun reportFailure(run: Attempt, index: Int, question: String, reason: String) {
        val batch = run.batch
        if (batch.pending.remove(question) == null) return
        batch.failed++
        Log.w(TAG, "Question $index failed: $reason")
        val elapsed = SystemClock.elapsedRealtime() - batch.started
        reply(batch.client, ProducerEvent.ItemFailed(index, question, elapsed, reason))
    }

    private fun watchBatch(run: Attempt) = armTimeout(run) {
        if (attempt !== run) return@armTimeout
        run.inFlight?.let { reportFailure(run, it.index, it.questionId, "裁图或写盘超时") }
        run.ready.forEach { reportFailure(run, it.index, it.questionId, "等待裁图超时") }
        run.waiting.forEach { (question, index) -> reportFailure(run, index, question, "等待题目就绪超时") }
        finish(run, false)
    }

    private fun armTimeout(run: Attempt, timeout: () -> Unit) {
        disarmTimeout(run)
        run.timeout = Runnable(timeout).also { main.postDelayed(it, 20_000) }
    }

    private fun disarmTimeout(run: Attempt) {
        run.timeout?.let(main::removeCallbacks)
        run.timeout = null
    }

    private fun finish(run: Attempt, cancelled: Boolean) {
        val batch = run.batch
        val elapsedMs = SystemClock.elapsedRealtime() - batch.started
        release(run)
        reply(batch.client, ProducerEvent.Done(batch.succeeded, batch.failed, cancelled, batch.first, elapsedMs))
    }

    private fun abort(run: Attempt, reason: String) {
        if (attempt !== run) return
        release(run)
        reply(run.batch.client, ProducerEvent.Error(reason))
    }

    private fun release(run: Attempt) {
        attempt = null
        disarmTimeout(run)
        page?.document?.clear()
    }

    private fun closePage() {
        val current = offscreen
        offscreen = null
        current?.close()
    }

    private fun phase(run: Attempt, name: String) {
        if (run.batch.first < 0) reply(run.batch.client, ProducerEvent.Phase(name))
    }

    private fun reply(client: Messenger, event: ProducerEvent) {
        try {
            client.send(ProducerProtocol.message(event))
        } catch (failure: RemoteException) {
            Log.w(TAG, "Batch client disconnected", failure)
        }
    }

    fun close() {
        attempt?.let(::release)
        closePage()
        writer.shutdown()
    }

    private companion object { const val TAG = "TechLabProducer" }
}

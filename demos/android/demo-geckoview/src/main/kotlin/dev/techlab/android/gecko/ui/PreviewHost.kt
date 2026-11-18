package dev.techlab.android.gecko.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.FrameLayout
import dev.techlab.android.gecko.RenderWarmup
import dev.techlab.android.gecko.databinding.ActivityGeckoDemoBinding
import dev.techlab.android.gecko.engine.GeckoPage
import dev.techlab.android.gecko.engine.PageRenderer
import dev.techlab.android.gecko.engine.RenderDocument
import dev.techlab.android.gecko.engine.SystemWebPreview
import java.util.UUID
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/** Owns only the live previews mounted by this screen. All callbacks run on the main thread. */
internal class PreviewHost(
    private val binding: ActivityGeckoDemoBinding,
    private val renderer: PageRenderer,
    private val model: RenderViewModel,
    private val deferInput: Boolean,
) {
    private data class MountedGecko(val page: GeckoPage, val view: GeckoView)

    private val main = Handler(Looper.getMainLooper())
    private var gecko: MountedGecko? = null
    private var web: SystemWebPreview? = null
    private var geckoRetryStart: Long? = null
    private val state get() = model.state.value

    init {
        binding.geckoOverlay.pageToView = { matrix ->
            gecko?.page?.session?.let {
                it.getPageToSurfaceMatrix(matrix)
                true
            } ?: false
        }
        binding.geckoFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> deliver(Mode.GECKO) }
        binding.webFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> deliver(Mode.WEBVIEW) }
    }

    private fun document(engine: Mode) = when (engine) {
        Mode.GECKO -> gecko?.page?.document
        Mode.WEBVIEW -> web?.document
        Mode.PRODUCER -> null
    }

    private fun mount(engine: Mode, fresh: Boolean): RenderDocument {
        val context = binding.root.context
        val doc = when (engine) {
            Mode.GECKO -> {
                val page = RenderWarmup.takeGecko(renderer, fresh)
                try {
                    val view = GeckoView(context)
                    gecko = MountedGecko(page, view)
                    binding.geckoFrame.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
                    page.onComposite = binding.geckoOverlay::postInvalidateOnAnimation
                    page.session.compositorScrollDelegate = object : GeckoSession.CompositorScrollDelegate {
                        override fun onScrollChanged(session: GeckoSession, update: GeckoSession.ScrollPositionUpdate) {
                            binding.geckoOverlay.postInvalidateOnAnimation()
                        }
                    }
                    view.setSession(page.session)
                } catch (failure: Exception) {
                    if (gecko == null) page.close()
                    throw failure
                }
                page.document
            }
            Mode.WEBVIEW -> {
                val page = RenderWarmup.takeWeb(context, fresh)
                web = page
                binding.geckoOverlay.layers.copyInto(page.layers)
                binding.webFrame.addView(page.frame, FrameLayout.LayoutParams(-1, -1))
                page.document
            }
            Mode.PRODUCER -> error("后台页面由生产进程持有")
        }
        doc.receive = { data -> onGeometry(engine, data) }
        doc.onState = {
            if (document(engine) === doc) {
                val failure = doc.failure
                if (failure != null) failedDocument(engine, failure, !doc.incompatible)
                else {
                    model.pageState(engine, doc.preparationMs)
                    deliver(engine)
                }
            }
        }
        return doc
    }

    fun load(engine: Mode, fresh: Boolean, actionAt: Long) {
        if (engine == Mode.GECKO) geckoRetryStart = null
        if (fresh) release(engine)
        start(engine, fresh, SystemClock.elapsedRealtime(), actionAt)
    }

    private fun start(engine: Mode, fresh: Boolean, requestedAt: Long, actionAt: Long) {
        val token = UUID.randomUUID().toString()
        model.start(engine, token, requestedAt, actionAt)
        try {
            val doc = document(engine) ?: mount(engine, fresh)
            doc.failure?.let {
                failedDocument(engine, it, !doc.incompatible)
                return
            }
            model.pageState(engine, doc.preparationMs)
            if (engine == Mode.GECKO) binding.geckoOverlay.display(null) else web?.clearOverlay()
            armTimeout(engine, token)
            deliver(engine)
        } catch (failure: Exception) {
            failedDocument(engine, failure.message ?: "渲染页面初始化失败", recoverable = false)
        }
    }

    private fun deliver(engine: Mode) {
        val doc = document(engine)?.takeIf { it.isReady } ?: return
        val pending = state.runs[engine] ?: return
        val frame = if (engine == Mode.GECKO) binding.geckoFrame else binding.webFrame
        if (state.mode != engine || frame.width <= 0) return
        val group = renderer.catalog.groups.firstOrNull { it.type == state.question } ?: return
        val start = geckoRetryStart.takeIf { engine == Mode.GECKO } ?: doc.renderStart(pending.requestedAt)
        val run = model.previewInput(engine, start, SystemClock.elapsedRealtime()) ?: return
        try {
            doc.setQuestions(renderer.catalog.request(run.token, group, background = false), deferInput)
        } catch (failure: Exception) {
            doc.fail(failure.toString())
        }
    }

    fun visible(engine: Mode) {
        if (engine == Mode.GECKO && gecko?.page?.session?.isOpen == false) {
            failedDocument(engine, "Gecko 会话已关闭", recoverable = true)
        } else deliver(engine)
    }

    private fun armTimeout(engine: Mode, token: String) {
        main.removeCallbacksAndMessages(null)
        main.postDelayed({
            val run = state.runs[engine]
            if (run?.token == token && run.running) {
                if (engine == Mode.GECKO && run.firstMs == null) failedDocument(engine, "等待排版结果超时", true)
                else {
                    cancel(engine)
                    model.fail(engine, "等待排版结果超时")
                }
            }
        }, 30_000)
    }

    private fun failedDocument(engine: Mode, reason: String, recoverable: Boolean) {
        val run = state.runs[engine]
        val now = SystemClock.elapsedRealtime()
        val requestedAt = run?.takeIf { it.running }?.requestedAt ?: now
        val start = document(engine)?.renderStart(requestedAt) ?: requestedAt
        release(engine)
        if (engine != state.mode) {
            model.unmount(engine)
            return
        }
        main.removeCallbacksAndMessages(null)
        if (engine == Mode.GECKO && recoverable && geckoRetryStart == null) {
            geckoRetryStart = start
            Log.w("TechLabPreview", "$reason · 重建 Gecko 会话")
            start(engine, false, requestedAt, run?.takeIf { it.running }?.actionAt ?: now)
        } else model.fail(engine, reason, releasePage = true)
    }

    private fun onGeometry(engine: Mode, data: JSONObject) {
        val run = state.runs[engine] ?: return
        if (data.optString("token") != run.token || run.error != null) return
        if (data.optString("type") == "error") {
            model.fail(engine, data.optString("message"))
            return
        }
        if (data.optString("type") != "geometry" || data.optString("coordinateSpace") != "document-css-pixels") return
        if (engine == Mode.GECKO) binding.geckoOverlay.display(data.getJSONArray("geometry")) else web?.display(data)
        if (!run.running) return
        val now = SystemClock.elapsedRealtime()
        if (run.firstMs == null) Log.i("TechLabFirst", "$engine token=${run.token} actionToFirstMs=${now - run.actionAt} " +
            "doc=${document(engine)?.navigationAt} requestToInputMs=${run.inputAt?.minus(run.startedAt)} " +
            "firstMs=${now - run.startedAt} page=${data.optJSONObject("pageTiming")}")
        val settled = data.optBoolean("settled")
        if (settled && state.mode == engine) main.removeCallbacksAndMessages(null)
        if (settled && engine == Mode.GECKO) geckoRetryStart = null
        val warning = listOfNotNull(
            data.optInt("missingImages").takeIf { it > 0 }?.let { "图片失败 $it" },
            data.optInt("formulaFallbacks").takeIf { it > 0 }?.let { "公式降级 $it" },
        ).joinToString(" · ")
        model.geometry(engine, run.token, now, settled, warning)
    }

    fun cancel(engine: Mode) {
        main.removeCallbacksAndMessages(null)
        document(engine)?.clear()
    }

    fun updateLayers() {
        binding.geckoOverlay.invalidate()
        web?.let {
            binding.geckoOverlay.layers.copyInto(it.layers)
            it.invalidateOverlay()
        }
    }

    private fun release(engine: Mode, recycle: Boolean = false) {
        when (engine) {
            Mode.GECKO -> gecko?.let { (page, view) ->
                gecko = null
                page.document.detach()
                page.onComposite = {}
                page.session.compositorScrollDelegate = null
                view.releaseSession()
                binding.geckoFrame.removeView(view)
                binding.geckoOverlay.display(null)
                if (recycle) RenderWarmup.recycle(page) else page.close()
            }
            Mode.WEBVIEW -> web?.let { page ->
                web = null
                page.document.detach()
                binding.webFrame.removeView(page.frame)
                page.clearOverlay()
                if (recycle) RenderWarmup.recycle(page) else page.close()
            }
            Mode.PRODUCER -> Unit
        }
    }

    fun close() {
        main.removeCallbacksAndMessages(null)
        release(Mode.GECKO, recycle = true)
        release(Mode.WEBVIEW, recycle = true)
    }
}

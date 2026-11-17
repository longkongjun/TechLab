package dev.techlab.android.gecko

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.ProgressBar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class GeckoDemoActivity : Activity() {
    private enum class Mode(val title: String) { GECKO("GeckoView"), WEBVIEW("WebView"), PRODUCER("后台产图") }

    private class EngineStartup(val startedAt: Long) {
        var elapsedMs: Long? = null
    }

    private class PreviewRun(val token: String, val startedAt: Long) {
        var firstMs: Long? = null
        var settledMs: Long? = null
        var warning = ""
        var error: String? = null
        val running get() = settledMs == null && error == null
    }

    private class ProducerRun(val total: Int, receive: (ProducerRun, Message) -> Boolean) {
        // A batch owns its reply channel, so cancelled batches cannot update newer results.
        val replies = Messenger(Handler(Looper.getMainLooper()) { receive(this, it) })
        var startupMs: Long? = null
        var renderStartedAt = 0L
        var preRenderMs = 0L
        var firstMs: Long? = null
        var finishedMs: Long? = null
        var succeeded = 0
        var failed = 0
        var phase = "页面"
        var firstBreakdown: String? = null
        var error: String? = null
        val running get() = finishedMs == null && error == null
    }

    private lateinit var web: GeckoView
    private lateinit var preview: FrameLayout
    private lateinit var previewOverlay: GeometryOverlay
    private lateinit var systemFrame: FrameLayout
    private lateinit var repeatPreview: Button
    private lateinit var newInstanceSwitch: Switch
    private lateinit var producerView: LinearLayout
    private lateinit var results: ProducerResultsAdapter
    private lateinit var resultList: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var picker: Button
    private lateinit var status: TextView
    private lateinit var previewNotice: TextView
    private val layers = BooleanArray(4)
    private var renderer: PageRenderer? = null
    private val geckoReady get() = engineStartups[Mode.GECKO]?.elapsedMs != null
    private var generation = 0
    private var mode = Mode.GECKO
    private var session: GeckoSession? = null
    private var geckoRecoveryAttempted = false
    private var systemPreview: SystemWebPreview? = null
    private var selected: QuestionCatalog.Group? = null
    private val engineStartups = mutableMapOf<Mode, EngineStartup>()
    private val previewRuns = mutableMapOf<Mode, PreviewRun>()
    private val previousTimings = mutableMapOf<Mode, String>()
    private var geckoInitError: String? = null
    private var webInitError: String? = null
    private var bound = false
    private var service: Messenger? = null
    private var serviceError: String? = null
    private var producerRun: ProducerRun? = null
    private val main = Handler(Looper.getMainLooper())
    private val timingClock = object : Runnable {
        override fun run() {
            val previewRun = previewRuns[mode]?.takeIf { it.running }
            if (previewRun != null && SystemClock.elapsedRealtime() - previewRun.startedAt >= 30_000) {
                val current = session
                if (mode == Mode.GECKO && current != null && previewRun.firstMs == null) {
                    recoverGecko(current, "等待排版结果超时")
                    return
                }
                stopPreviewLoad()
                failPreview(mode, "等待排版结果超时")
                return
            }
            drawPreviewTiming()
            if (previewRun != null || producerRun?.running == true) main.postDelayed(this, 250)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            serviceError = null
            if (mode == Mode.PRODUCER && producerRun == null) loadCurrent()
            drawPreviewTiming()
            refreshButtons()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            serviceError = "后台渲染服务已断开"
            producerRun?.takeIf { it.running }?.error = serviceError
            if (mode == Mode.PRODUCER) main.removeCallbacks(timingClock)
            updateProducer()
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        mode = Mode.entries.firstOrNull { it.name == state?.getString("mode") } ?: Mode.GECKO
        state?.getBooleanArray("layers")?.copyInto(layers)
        for (engine in Mode.entries) {
            // Older saved producer rows also included the breakdown below the comparison line.
            state?.getString("renderTiming.${engine.name}")?.let { previousTimings[engine] = it.substringBefore('\n') }
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabs = row()
        val modeContent = FrameLayout(this).apply { setPadding(10.dp, 0, 10.dp, 0) }
        preview = FrameLayout(this)
        systemFrame = FrameLayout(this)
        previewOverlay = GeometryOverlay(this, layers) { matrix ->
            val current = session ?: return@GeometryOverlay false
            current.getPageToSurfaceMatrix(matrix)
            true
        }
        producerView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply {
            visibility = View.GONE
            setPadding(14.dp, 8.dp, 14.dp, 8.dp)
        }
        results = ProducerResultsAdapter(layers)
        resultList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@GeckoDemoActivity)
            adapter = results
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (mode == Mode.PRODUCER && producerRun == null) loadCurrent()
            }
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
        }
        picker = button("选择题型") { chooseType() }
        previewNotice = TextView(this).apply {
            setPadding(6.dp, 0, 6.dp, 8.dp)
        }
        repeatPreview = button("重测当前") { retestCurrent() }
        newInstanceSwitch = Switch(this).apply {
            text = "新建实例"
            switchPadding = 8.dp
            isChecked = state?.getBoolean("newInstanceOnRetest") ?: false
        }
        val retestActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.END
            addView(newInstanceSwitch)
            addView(repeatPreview)
        }
        val previewTools = row().apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(previewNotice, LinearLayout.LayoutParams(0, -2, 1f))
            addView(retestActions)
        }
        val toggles = row()
        listOf("题目框", "行信息", "优先批改框", "基线").forEachIndexed { index, label ->
            toggles.addView(CheckBox(this).apply {
                text = label
                isChecked = layers[index]
                setOnCheckedChangeListener { _, checked ->
                    layers[index] = checked
                    results.refreshLayers()
                    previewOverlay.invalidate()
                    systemPreview?.invalidateOverlay()
                }
            })
        }
        producerView.addView(status)
        producerView.addView(progress, LinearLayout.LayoutParams(-1, -2))
        producerView.addView(resultList, LinearLayout.LayoutParams(-1, 0, 1f))
        for ((target, title) in listOf(Mode.GECKO to "GeckoView 排版", Mode.WEBVIEW to "WebView 排版", Mode.PRODUCER to "后台产图")) {
            tabs.addView(button(title) { showMode(target) }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(tabs)
        root.addView(picker, LinearLayout.LayoutParams(-1, -2))
        root.addView(toggles)
        root.addView(previewTools)
        root.addView(modeContent, LinearLayout.LayoutParams(-1, 0, 1f))
        preview.addView(previewOverlay, FrameLayout.LayoutParams(-1, -1))
        modeContent.addView(preview, FrameLayout.LayoutParams(-1, -1))
        modeContent.addView(systemFrame, FrameLayout.LayoutParams(-1, -1))
        modeContent.addView(producerView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        try {
            val local = PageRenderer.get(this, "preview")
            renderer = local
            val savedType = state?.getString("questionType")
            selected = local.catalog.groups.firstOrNull { it.type.name == savedType } ?: local.catalog.groups.first()
            updatePicker()
        } catch (failure: Exception) {
            failEngine(Mode.GECKO, failure.message ?: "题型目录加载失败")
        }
        showMode(mode)
        bound = bindService(Intent(this, ProducerService::class.java), connection, BIND_AUTO_CREATE)
        if (!bound) {
            serviceError = "无法连接后台渲染服务"
            updateProducer()
        }
    }

    private fun startGecko(local: PageRenderer) {
        beginStartup(Mode.GECKO)
        web = GeckoView(this)
        preview.addView(web, 0, FrameLayout.LayoutParams(-1, -1))
        val current = GeckoSession().apply {
            contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onFirstComposite(session: GeckoSession) {
                    previewOverlay.postInvalidateOnAnimation()
                }

                override fun onCrash(session: GeckoSession) {
                    main.post { recoverGecko(session, "Gecko 内容进程崩溃") }
                }

                override fun onKill(session: GeckoSession) {
                    main.post { recoverGecko(session, "Gecko 内容进程被终止") }
                }
            }
            compositorScrollDelegate = object : GeckoSession.CompositorScrollDelegate {
                override fun onScrollChanged(session: GeckoSession, update: GeckoSession.ScrollPositionUpdate) {
                    previewOverlay.postInvalidateOnAnimation()
                }
            }
        }
        session = current
        current.open(local.runtime)
        web.setSession(current)
        local.attach(current, { token -> previewInput(Mode.GECKO, token) }, { data ->
            onPreviewGeometry(Mode.GECKO, data)
        }, {
            if (session === current) {
                completeStartup(Mode.GECKO)
                if (mode == Mode.GECKO) loadPreview()
            }
        }, {
            if (session === current) failEngine(Mode.GECKO, it.message ?: "消息桥初始化失败")
        })
    }

    private fun showMode(target: Mode) {
        if (target != mode) {
            if (target == Mode.GECKO) geckoRecoveryAttempted = false
            if (mode == Mode.PRODUCER && producerRun?.running == true) clearProducer()
            if (previewRuns[mode]?.running == true) {
                previewRuns.remove(mode)
                stopPreviewLoad()
            }
        }
        mode = target
        newInstanceSwitch.visibility = if (target == Mode.PRODUCER) View.GONE else View.VISIBLE
        preview.visibility = if (target == Mode.GECKO) View.VISIBLE else View.GONE
        systemFrame.visibility = if (target == Mode.WEBVIEW) View.VISIBLE else View.GONE
        producerView.visibility = if (target == Mode.PRODUCER) View.VISIBLE else View.GONE
        if (target == Mode.GECKO) {
            session?.takeUnless { it.isOpen }?.let {
                recoverGecko(it, "Gecko 会话已关闭")
                return
            }
        }
        drawPreviewTiming()
        refreshButtons()
        val hasRun = if (target == Mode.PRODUCER) producerRun != null else previewRuns[target] != null
        if (!hasRun) loadCurrent()
    }

    private fun chooseType() {
        val groups = renderer?.catalog?.groups ?: return
        val titles = groups.map { "${it.type.title}（${it.questions.size}）" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("选择题型")
            .setSingleChoiceItems(titles, groups.indexOf(selected)) { dialog, index ->
                dialog.dismiss()
                val next = groups[index]
                if (next === selected) return@setSingleChoiceItems
                selected = next
                geckoRecoveryAttempted = false
                stopPreviewLoad()
                previewRuns.clear()
                previousTimings.clear()
                clearProducer()
                updatePicker()
                drawPreviewTiming()
                loadCurrent()
            }.show()
    }

    private fun updatePicker() {
        picker.text = selected?.let { "题型：${it.type.title}（${it.questions.size}） ▾" } ?: "选择题型"
    }

    private fun loadCurrent() {
        if (isFinishing || isDestroyed) return
        if (mode == Mode.PRODUCER) submit() else loadPreview()
    }

    private fun retestCurrent() {
        if (mode != Mode.PRODUCER && newInstanceSwitch.isChecked) {
            main.removeCallbacks(timingClock)
            engineStartups.remove(mode)?.let { main.removeCallbacksAndMessages(it) }
            previewRuns.remove(mode)
            if (mode == Mode.GECKO) {
                previewOverlay.display(null)
                releaseGeckoSession()
            } else {
                releaseSystemPreview()
            }
        }
        if (mode == Mode.GECKO) {
            geckoInitError = null
            geckoRecoveryAttempted = false
        }
        loadCurrent()
    }

    private fun loadPreview() {
        val local = renderer ?: return
        if (isFinishing || isDestroyed || mode == Mode.PRODUCER || previewRuns[mode]?.running == true) return
        if (mode == Mode.GECKO && session == null && geckoInitError == null) {
            try {
                startGecko(local)
            } catch (failure: Exception) {
                failEngine(Mode.GECKO, failure.message ?: "GeckoView 初始化失败")
            }
            return
        }
        val gecko = if (mode == Mode.GECKO && geckoReady) session else null
        val androidWeb = if (mode == Mode.WEBVIEW) getSystemPreview() else null
        if (gecko == null && (androidWeb == null || engineStartups[Mode.WEBVIEW]?.elapsedMs == null)) return
        val token = (++generation).toString()
        val url = local.url(token)
        previewRuns[mode] = PreviewRun(token, SystemClock.elapsedRealtime())
        if (gecko != null) {
            previewOverlay.display(null)
            gecko.loadUri(url)
        } else {
            androidWeb?.load(url, token)
        }
        refreshButtons()
        timingClock.run()
    }

    private fun getSystemPreview(): SystemWebPreview? {
        systemPreview?.let { return it }
        beginStartup(Mode.WEBVIEW)
        return try {
            SystemWebPreview(this, layers, { token -> previewInput(Mode.WEBVIEW, token) }, { data ->
                onPreviewGeometry(Mode.WEBVIEW, data)
            }, {
                failEngine(Mode.WEBVIEW, "WebView 渲染进程已退出")
            }).also { current ->
                systemPreview = current
                systemFrame.addView(current.frame, 0, FrameLayout.LayoutParams(-1, -1))
                completeStartup(Mode.WEBVIEW)
            }
        } catch (failure: Exception) {
            failEngine(Mode.WEBVIEW, failure.message ?: "WebView 初始化失败")
            null
        }
    }

    private fun beginStartup(engine: Mode) {
        previousTimings.remove(engine)
        val startup = EngineStartup(SystemClock.elapsedRealtime())
        engineStartups[engine] = startup
        if (engine == Mode.WEBVIEW) webInitError = null
        drawPreviewTiming()
        refreshButtons()
        main.postAtTime({ failEngine(engine, "渲染实例准备超时") }, startup, SystemClock.uptimeMillis() + 30_000)
    }

    private fun completeStartup(engine: Mode) {
        val startup = engineStartups[engine] ?: return
        startup.elapsedMs = SystemClock.elapsedRealtime() - startup.startedAt
        main.removeCallbacksAndMessages(startup)
        drawPreviewTiming()
        refreshButtons()
    }

    private fun failEngine(engine: Mode, reason: String) {
        engineStartups.remove(engine)?.let { main.removeCallbacksAndMessages(it) }
        previewRuns.remove(engine)
        if (engine == Mode.GECKO) {
            geckoInitError = reason
            releaseGeckoSession()
        } else {
            webInitError = reason
            releaseSystemPreview()
        }
        if (engine == mode) main.removeCallbacks(timingClock)
        drawPreviewTiming()
        refreshButtons()
    }

    private fun recoverGecko(failed: GeckoSession, reason: String) {
        if (session !== failed || isFinishing || isDestroyed) return
        Log.w("TechLabPreview", "$reason · 当前模式 ${mode.name}")
        if (mode == Mode.GECKO && geckoRecoveryAttempted) {
            failEngine(Mode.GECKO, reason)
            return
        }
        if (previewRuns[Mode.GECKO]?.settledMs != null) {
            previousTimings[Mode.GECKO] = previewTiming(Mode.GECKO, SystemClock.elapsedRealtime())
        }
        previewRuns.remove(Mode.GECKO)
        engineStartups.remove(Mode.GECKO)?.let { main.removeCallbacksAndMessages(it) }
        previewOverlay.display(null)
        releaseGeckoSession()
        if (mode == Mode.GECKO) {
            main.removeCallbacks(timingClock)
            geckoRecoveryAttempted = true
            loadPreview()
        } else {
            drawPreviewTiming()
        }
    }

    private fun releaseGeckoSession() {
        val current = session
        session = null
        if (::web.isInitialized) {
            web.releaseSession()
            preview.removeView(web)
        }
        if (current?.isOpen == true) current.close()
    }

    private fun releaseSystemPreview() {
        val current = systemPreview
        systemPreview = null
        systemFrame.removeAllViews()
        current?.close()
    }

    private fun previewInput(engine: Mode, token: String): JSONObject? {
        val group = selected ?: return null
        if (previewRuns[engine]?.token != token) return null
        return renderer?.catalog?.request(token, group, background = false)
    }

    private fun onPreviewGeometry(engine: Mode, data: JSONObject) {
        val run = previewRuns[engine] ?: return
        if (data.optString("token") != run.token || run.error != null) return
        if (data.optString("type") == "error") {
            failPreview(engine, data.optString("message"))
            return
        }
        if (data.optString("type") != "geometry" || data.optString("coordinateSpace") != "document-css-pixels") return
        val elapsed = SystemClock.elapsedRealtime() - run.startedAt
        if (engine == Mode.GECKO) previewOverlay.display(data.getJSONArray("geometry")) else systemPreview?.display(data)
        if (!run.running) return
        if (run.firstMs == null) run.firstMs = elapsed
        if (data.optBoolean("settled")) {
            run.settledMs = elapsed
            if (engine == Mode.GECKO) geckoRecoveryAttempted = false
            val missing = data.optInt("missingImages")
            val fallbacks = data.optInt("formulaFallbacks")
            run.warning = listOfNotNull(
                "图片失败 $missing".takeIf { missing > 0 },
                "公式降级 $fallbacks".takeIf { fallbacks > 0 },
            ).joinToString(" · ")
            if (mode == engine) main.removeCallbacks(timingClock)
            refreshButtons()
        }
        drawPreviewTiming()
    }

    private fun drawPreviewTiming() {
        val now = SystemClock.elapsedRealtime()
        previewNotice.text = listOf(
            previewTiming(Mode.GECKO, now), previewTiming(Mode.WEBVIEW, now), producerTiming(now),
        ).joinToString("\n")
    }

    private fun durationText(elapsed: Long?) = elapsed?.let { "$it ms" } ?: "—"

    private fun timingLine(mode: Mode, preparation: Long?, first: Long?, completed: Long?, status: String = ""): String {
        val times = "${mode.title} · 准备 ${durationText(preparation)} · 首屏 ${durationText(first)} · 完成 ${durationText(completed)}"
        return if (status.isEmpty()) times else "$times · $status"
    }

    private fun previewTiming(engine: Mode, now: Long): String {
        val run = previewRuns[engine]
        val initError = if (engine == Mode.GECKO) geckoInitError else webInitError
        val startup = engineStartups[engine]
        if (run == null && startup == null && initError == null) {
            previousTimings[engine]?.let { return "$it · 上次" }
        }
        val state = when {
            initError != null -> initError
            run?.error != null -> run.error.orEmpty()
            startup != null && startup.elapsedMs == null -> "准备中"
            run?.running == true -> "处理中 ${now - run.startedAt} ms"
            else -> run?.warning.orEmpty()
        }
        return timingLine(engine, startup?.elapsedMs, run?.firstMs, run?.settledMs, state)
    }

    private fun producerTiming(now: Long): String {
        val run = producerRun ?: return previousTimings[Mode.PRODUCER]?.let { "$it · 上次" }
            ?: timingLine(Mode.PRODUCER, null, null, null, serviceError.orEmpty())
        val state = when {
            run.error != null -> run.error.orEmpty()
            run.running && run.renderStartedAt > 0 ->
                "${if (run.firstMs == null) run.phase else "处理中"} ${now - run.renderStartedAt} ms"
            run.running -> "准备中"
            else -> ""
        }
        return timingLine(Mode.PRODUCER, run.startupMs, run.firstMs, run.finishedMs, state)
    }

    private fun stopPreviewLoad() {
        main.removeCallbacks(timingClock)
        // A filter change can cancel the question page, while the engine continues starting.
        if (engineStartups[mode]?.elapsedMs == null) return
        when (mode) {
            Mode.GECKO -> session?.run {
                stop()
                loadUri("about:blank")
            }
            Mode.WEBVIEW -> systemPreview?.stop()
            Mode.PRODUCER -> Unit
        }
    }

    private fun failPreview(engine: Mode, reason: String) {
        previewRuns[engine]?.error = reason
        if (engine == mode) main.removeCallbacks(timingClock)
        drawPreviewTiming()
        refreshButtons()
    }

    private fun submit() {
        val group = selected ?: return
        if (service == null || producerRun?.running == true || resultList.width <= 0) return
        previousTimings.remove(Mode.PRODUCER)
        results.clear()
        val run = ProducerRun(group.questions.size, ::onResult)
        producerRun = run
        val args = Bundle().apply {
            putString("questionType", group.type.name)
            putInt("width", minOf(resultList.width, ProducerService.MAX_RENDER_WIDTH))
        }
        if (!command(run, ProducerService.START, args)) run.error = serviceError
        updateProducer()
        if (run.running) timingClock.run()
    }

    private fun command(run: ProducerRun, type: Int, args: Bundle = Bundle()): Boolean {
        val remote = service ?: return false
        return try {
            remote.send(Message.obtain(null, type).apply {
                data = args
                replyTo = run.replies
            })
            true
        } catch (_: RemoteException) {
            service = null
            serviceError = "后台渲染服务已断开"
            false
        }
    }

    private fun onResult(run: ProducerRun, message: Message): Boolean {
        if (producerRun !== run || !run.running) return true
        val data = message.data
        when (message.what) {
            ProducerService.STARTED -> {
                run.startupMs = data.getLong("startupMs")
                run.renderStartedAt = data.getLong("renderStartedAt")
                // The per-question times still use the whole batch as their origin.
                run.preRenderMs = run.renderStartedAt - data.getLong("startedAt")
            }
            ProducerService.ITEM -> {
                run.succeeded++
                if (run.firstMs == null) {
                    run.firstMs = data.getLong("elapsedMs") - run.preRenderMs
                    run.firstBreakdown = "页面 ${data.getLong("pageMs")} · 排版 ${data.getLong("layoutMs")} · " +
                        "截图 ${data.getLong("captureMs")} · 写盘 ${data.getLong("writeMs")} ms"
                }
                results.append(data)
            }
            ProducerService.PHASE -> run.phase = data.getString("phase") ?: run.phase
            ProducerService.ITEM_ERROR -> {
                run.failed++
                results.append(data)
            }
            ProducerService.DONE -> {
                run.succeeded = data.getInt("succeeded")
                run.failed = data.getInt("failed")
                if (data.getBoolean("cancelled")) run.error = "已取消"
                else run.finishedMs = data.getLong("elapsedMs") - run.preRenderMs
            }
            ProducerService.ERROR -> run.error = data.getString("error") ?: "后台产图失败"
            else -> return false
        }
        if (!run.running) main.removeCallbacks(timingClock)
        updateProducer()
        return true
    }

    private fun clearProducer() {
        val previous = producerRun
        producerRun = null
        if (previous?.running == true) {
            main.removeCallbacks(timingClock)
            command(previous, ProducerService.CANCEL)
        }
        results.clear()
        drawProgress()
    }

    private fun updateProducer() {
        drawProgress()
        drawPreviewTiming()
        refreshButtons()
    }

    private fun drawProgress() {
        val run = producerRun
        status.visibility = if (run == null) View.GONE else View.VISIBLE
        progress.visibility = if (run == null) View.GONE else View.VISIBLE
        if (run == null) return
        val completed = run.succeeded + run.failed
        status.text = buildString {
            append("$completed/${run.total} · 成功 ${run.succeeded} · 失败 ${run.failed}")
            run.firstBreakdown?.let { append("\n首屏分段 · $it") }
        }
        progress.max = run.total
        progress.progress = completed
    }

    private fun refreshButtons() {
        picker.isEnabled = renderer != null
        val canRepeat = when (mode) {
            Mode.GECKO, Mode.WEBVIEW -> engineStartups[mode]?.let { it.elapsedMs != null } ?: true
            Mode.PRODUCER -> service != null && producerRun?.running != true
        }
        repeatPreview.isEnabled = renderer != null && canRepeat && previewRuns[mode]?.running != true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("questionType", selected?.type?.name)
        outState.putString("mode", mode.name)
        outState.putBoolean("newInstanceOnRetest", newInstanceSwitch.isChecked)
        outState.putBooleanArray("layers", layers.copyOf())
        val now = SystemClock.elapsedRealtime()
        for (engine in Mode.entries) {
            val timing = if (engine == Mode.PRODUCER) {
                producerRun?.finishedMs?.let { producerTiming(now) }
            } else {
                previewRuns[engine]?.settledMs?.let { previewTiming(engine, now) }
            } ?: previousTimings[engine]
            if (timing != null) outState.putString("renderTiming.${engine.name}", timing)
        }
        super.onSaveInstanceState(outState)
    }

    private val Int.dp get() = (this * resources.displayMetrics.density + .5f).toInt()

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(8.dp, 4.dp, 8.dp, 0)
    }

    private fun button(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { click() }
    }

    override fun onDestroy() {
        clearProducer()
        if (bound) unbindService(connection)
        releaseGeckoSession()
        releaseSystemPreview()
        main.removeCallbacksAndMessages(null)
        resultList.adapter = null
        results.close()
        super.onDestroy()
    }
}

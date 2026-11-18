package dev.techlab.android.gecko

import android.app.AlertDialog
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.techlab.android.gecko.databinding.ActivityGeckoDemoBinding
import dev.techlab.android.gecko.engine.PageRenderer
import dev.techlab.android.gecko.producer.BatchCommand
import dev.techlab.android.gecko.producer.ProducerClient
import dev.techlab.android.gecko.producer.ProducerEvent
import dev.techlab.android.gecko.ui.Mode
import dev.techlab.android.gecko.ui.PreviewHost
import dev.techlab.android.gecko.ui.ProducerResultsAdapter
import dev.techlab.android.gecko.ui.RenderAction
import dev.techlab.android.gecko.ui.RenderScreenState
import dev.techlab.android.gecko.ui.RenderViewModel
import dev.techlab.android.gecko.ui.RenderWork
import dev.techlab.android.gecko.ui.TypeOption
import dev.techlab.android.gecko.ui.restoreRenderScreen
import dev.techlab.android.gecko.ui.saveTo
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

const val EXTRA_DEFER_RENDER_INPUT = "deferRenderInput"

class GeckoDemoActivity : ComponentActivity() {
    private val model by viewModels<RenderViewModel>()
    private val binding by lazy(LazyThreadSafetyMode.NONE) { ActivityGeckoDemoBinding.inflate(layoutInflater) }
    private val resultAdapter by lazy(LazyThreadSafetyMode.NONE) { ProducerResultsAdapter(binding.geckoOverlay.layers) }
    private val producer by lazy(LazyThreadSafetyMode.NONE) {
        ProducerClient(this, ::onConnection, ::onProducerEvent)
    }
    private var preview: PreviewHost? = null
    private var actionAt = SystemClock.elapsedRealtime()
    private var renderedLayers: Int? = null
    private val deferInput get() = intent.getBooleanExtra(EXTRA_DEFER_RENDER_INPUT, false)
    private val state get() = model.state.value
    private val layerControls by lazy(LazyThreadSafetyMode.NONE) {
        listOf(binding.questionBoxes, binding.lineInfo, binding.checkBoxes, binding.baselines)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        val renderer = runCatching { PageRenderer.preview(this) }
        val options = renderer.getOrNull()?.catalog?.groups.orEmpty().map { TypeOption(it.type, it.questions.size) }
        model.attach(options, savedInstanceState?.restoreRenderScreen())
        renderer.onSuccess { preview = PreviewHost(binding, it, model, deferInput) }
            .onFailure { model.fail(state.mode, it.message ?: "题型目录加载失败") }

        binding.results.adapter = resultAdapter
        binding.results.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> loadProducerIfNeeded() }
        binding.geckoTab.setOnClickListener { dispatch(RenderAction.SelectMode(Mode.GECKO)) }
        binding.webTab.setOnClickListener { dispatch(RenderAction.SelectMode(Mode.WEBVIEW)) }
        binding.producerTab.setOnClickListener { dispatch(RenderAction.SelectMode(Mode.PRODUCER)) }
        binding.picker.setOnClickListener { chooseType() }
        binding.retest.setOnClickListener { dispatch(RenderAction.Retest) }
        binding.freshInstance.setOnCheckedChangeListener { _, checked -> model.dispatch(RenderAction.SetFresh(checked)) }
        layerControls.forEachIndexed { index, box ->
            box.setOnCheckedChangeListener { _, checked -> model.dispatch(RenderAction.SetLayer(index, checked)) }
        }
        render(state)
        load()
        producer.bind()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collectLatest { screen ->
                    render(screen)
                    while (screen.runs[screen.mode]?.running == true) {
                        delay(250)
                        drawTimings(screen)
                    }
                }
            }
        }
    }

    private fun dispatch(action: RenderAction) {
        actionAt = SystemClock.elapsedRealtime()
        val work = model.dispatch(action)
        showMode(state.mode)
        work.forEach {
            when (it) {
                is RenderWork.Cancel -> if (it.mode == Mode.PRODUCER) producer.cancel() else preview?.cancel(it.mode)
                is RenderWork.Load -> load(it.fresh)
            }
        }
        if (action is RenderAction.SelectMode && state.mode != Mode.PRODUCER) preview?.visible(state.mode)
    }

    private fun chooseType() {
        val choices = state.options
        AlertDialog.Builder(this).setTitle(R.string.picker)
            .setSingleChoiceItems(choices.map { it.label }.toTypedArray(), choices.indexOf(state.selected)) { dialog, index ->
                dialog.dismiss()
                dispatch(RenderAction.SelectType(choices[index].type))
            }.show()
    }

    private fun load(fresh: Boolean = false) {
        if (isFinishing || isDestroyed || state.selected == null) return
        val mode = state.mode
        if (mode != Mode.PRODUCER) {
            preview?.load(mode, fresh, actionAt)
            return
        }
        val width = binding.results.width.coerceAtMost(ProducerService.MAX_RENDER_WIDTH)
        if (!state.connected || width <= 0 || state.runs[mode]?.running == true) return
        val token = UUID.randomUUID().toString()
        val request = BatchCommand.Start(checkNotNull(state.question).name, width, deferInput = deferInput, sampleMemory = false)
        model.start(mode, token, SystemClock.elapsedRealtime(), actionAt)
        producer.start(token, request)
    }

    private fun loadProducerIfNeeded() {
        if (state.mode == Mode.PRODUCER && state.runs[Mode.PRODUCER] == null) load()
    }

    private fun onConnection(connected: Boolean, error: String?) {
        model.connection(connected, error)
        if (connected) loadProducerIfNeeded()
    }

    private fun onProducerEvent(token: String, event: ProducerEvent) {
        val run = state.runs[Mode.PRODUCER]
        if (event is ProducerEvent.Written && run?.token == token && run.running && run.firstMs == null) {
            val firstMs = event.elapsedMs - (state.batch?.preRenderMs ?: 0)
            Log.i("TechLabFirst", "PRODUCER token=${event.token} actionToItemMs=${SystemClock.elapsedRealtime() - run.actionAt} " +
                "firstMs=$firstMs id=${event.questionId}")
        }
        model.producerEvent(token, event)
    }

    private fun showMode(mode: Mode) = with(binding) {
        geckoFrame.isVisible = mode == Mode.GECKO
        webFrame.isVisible = mode == Mode.WEBVIEW
        producerPanel.isVisible = mode == Mode.PRODUCER
        freshInstance.isVisible = mode != Mode.PRODUCER
    }

    private fun render(screen: RenderScreenState) = with(binding) {
        showMode(screen.mode)
        picker.isEnabled = screen.options.isNotEmpty()
        picker.text = screen.selected?.let { "题型：${it.label} ▾" } ?: getString(R.string.picker)
        retest.isEnabled = screen.canRetest
        freshInstance.isChecked = screen.fresh
        if (renderedLayers != screen.layers) {
            renderedLayers = screen.layers
            layerControls.forEachIndexed { index, box ->
                val checked = screen.layers and (1 shl index) != 0
                geckoOverlay.layers[index] = checked
                box.isChecked = checked
            }
            resultAdapter.refreshLayers()
            preview?.updateLayers()
        }
        val batch = screen.batch
        progress.isVisible = batch != null
        status.isVisible = batch != null
        if (batch != null) {
            val completed = batch.succeeded + batch.failed
            progress.max = batch.total
            progress.progress = completed
            status.text = buildString {
                append("$completed/${batch.total} · 成功 ${batch.succeeded} · 失败 ${batch.failed}")
                batch.stages?.let { append("\n首屏分段 · 页面 ${it.page} · 排版 ${it.layout} · 截图 ${it.capture} · 写盘 ${it.write} ms") }
            }
        }
        resultAdapter.submitList(batch?.rows.orEmpty())
        drawTimings(screen)
    }

    private fun drawTimings(screen: RenderScreenState) {
        val now = SystemClock.elapsedRealtime()
        binding.timings.text = Mode.entries.joinToString("\n") { screen.timingLine(it, now) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        state.saveTo(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        producer.close()
        preview?.close()
        binding.results.adapter = null
        resultAdapter.close()
        model.detach()
        super.onDestroy()
    }
}

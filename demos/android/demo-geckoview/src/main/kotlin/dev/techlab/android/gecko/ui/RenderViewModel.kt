package dev.techlab.android.gecko.ui

import androidx.lifecycle.ViewModel
import dev.techlab.android.gecko.producer.ProducerEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class RenderViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(RenderScreenState())
    val state = mutableState.asStateFlow()
    private var current: RenderScreenState
        get() = mutableState.value
        set(value) { mutableState.value = value }

    private var attachedBefore = false

    fun attach(options: List<TypeOption>, restored: RenderScreenState?) {
        val selection = if (attachedBefore) current else restored ?: current
        attachedBefore = true
        val question = selection.question?.takeIf { saved -> options.any { it.type == saved } }
        current = selection.copy(options = options, question = question ?: options.firstOrNull()?.type)
    }

    fun dispatch(action: RenderAction): List<RenderWork> {
        val old = current
        return when (action) {
            is RenderAction.SelectMode -> {
                if (action.mode == old.mode) return emptyList()
                val cancel = old.runs[old.mode]?.running == true
                current = old.copy(mode = action.mode,
                    runs = if (cancel) old.runs - old.mode else old.runs,
                    batch = old.batch.takeUnless { cancel && old.mode == Mode.PRODUCER })
                buildList {
                    if (cancel) add(RenderWork.Cancel(old.mode))
                    val run = current.runs[action.mode]
                    if (run == null || (action.mode != Mode.PRODUCER && run.error != null && action.mode !in current.preparation)) {
                        add(RenderWork.Load())
                    }
                }
            }
            is RenderAction.SelectType -> {
                if (old.question == action.type || old.options.none { it.type == action.type }) return emptyList()
                current = old.copy(question = action.type, runs = emptyMap(), history = emptyMap(),
                    legacyHistory = emptyMap(), batch = null, errors = emptyMap())
                listOf(RenderWork.Cancel(old.mode), RenderWork.Load())
            }
            RenderAction.Retest -> if (old.canRetest) listOf(RenderWork.Load(old.fresh && old.mode != Mode.PRODUCER)) else emptyList()
            is RenderAction.SetFresh -> {
                current = old.copy(fresh = action.checked)
                emptyList()
            }
            is RenderAction.SetLayer -> {
                val bit = 1 shl action.index
                current = old.copy(layers = if (action.checked) old.layers or bit else old.layers and bit.inv())
                emptyList()
            }
        }
    }

    fun start(engine: Mode, token: String, now: Long, actionAt: Long = now) {
        val old = current
        val producer = engine == Mode.PRODUCER
        current = old.copy(runs = old.runs + (engine to RenderRun(token, now, actionAt)),
            errors = old.errors - engine, history = old.history - engine, legacyHistory = old.legacyHistory - engine,
            preparation = if (producer) old.preparation - engine else old.preparation,
            batch = if (producer) BatchProgress(old.selected?.count ?: 0) else old.batch)
    }

    fun unmount(engine: Mode) {
        val old = current
        val last = old.completedTiming(engine)
        current = old.copy(preparation = old.preparation - engine, runs = old.runs - engine, errors = old.errors - engine,
            history = if (last == null) old.history else old.history + (engine to last))
    }

    fun pageState(engine: Mode, preparationMs: Long?) {
        current = current.copy(preparation = current.preparation + (engine to preparationMs), errors = current.errors - engine)
    }

    fun fail(engine: Mode, message: String, releasePage: Boolean = false) {
        val old = current
        val run = old.runs[engine]
        current = old.copy(errors = old.errors + (engine to message),
            runs = if (run == null) old.runs else old.runs + (engine to run.copy(error = message)),
            preparation = if (releasePage) old.preparation - engine else old.preparation)
    }

    fun previewInput(engine: Mode, start: Long, now: Long): RenderRun? {
        val run = current.runs[engine] ?: return null
        if (current.mode != engine || !run.running || run.inputAt != null) return null
        return run.copy(startedAt = start, inputAt = now).also { setRun(engine, it) }
    }

    fun geometry(engine: Mode, token: String, now: Long, settled: Boolean, warning: String) {
        val run = current.runs[engine]?.takeIf { it.token == token } ?: return
        setRun(engine, run.geometry(now, settled, warning))
    }

    fun connection(connected: Boolean, error: String? = null) {
        current = current.copy(connected = connected, errors = current.errors - Mode.PRODUCER)
        if (error != null) {
            val run = current.runs[Mode.PRODUCER]
            if (run == null || run.running) fail(Mode.PRODUCER, error)
        }
    }

    fun producerEvent(token: String, event: ProducerEvent) {
        val engine = Mode.PRODUCER
        val old = current
        val run = old.runs[engine]?.takeIf { it.token == token && it.running } ?: return
        val batch = old.batch ?: return
        fun updated(runState: RenderRun = run, progress: BatchProgress = batch) =
            old.copy(runs = old.runs + (engine to runState), batch = progress)

        current = when (event) {
            is ProducerEvent.Started -> updated(
                run.copy(startedAt = event.renderStartedAt),
                batch.copy(preRenderMs = event.renderStartedAt - event.startedAt),
            ).copy(preparation = old.preparation + (engine to event.preparationMs))
            is ProducerEvent.Phase -> updated(progress = batch.copy(phase = event.name))
            is ProducerEvent.Written -> updated(
                run.copy(firstMs = run.firstMs ?: (event.elapsedMs - batch.preRenderMs)),
                batch.copy(succeeded = batch.succeeded + 1, stages = batch.stages ?: event.stages, rows = batch.rows + event),
            )
            is ProducerEvent.ItemFailed -> updated(progress = batch.copy(failed = batch.failed + 1, rows = batch.rows + event))
            is ProducerEvent.Done -> updated(
                if (event.cancelled) run.copy(error = "已取消") else run.copy(finishedMs = event.elapsedMs - batch.preRenderMs),
                batch.copy(succeeded = event.succeeded, failed = event.failed),
            )
            is ProducerEvent.Error -> updated(run.copy(error = event.reason))
        }
    }

    fun detach() {
        val old = current
        val history = Mode.entries.mapNotNull { engine -> old.completedTiming(engine)?.let { engine to it } }.toMap()
        current = RenderScreenState(mode = old.mode, question = old.question, fresh = old.fresh, layers = old.layers,
            history = history, legacyHistory = old.legacyHistory)
    }

    private fun setRun(engine: Mode, run: RenderRun) {
        current = current.copy(runs = current.runs + (engine to run))
    }
}

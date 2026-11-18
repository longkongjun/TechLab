package dev.techlab.android.gecko.producer

import android.os.Bundle
import android.os.Message
import android.os.Messenger
import dev.techlab.android.gecko.EXTRA_DEFER_RENDER_INPUT
import dev.techlab.android.gecko.ProducerService

internal sealed interface BatchCommand {
    data class Start(
        val questionType: String,
        val width: Int,
        val startIndex: Int = 0,
        val limit: Int = 0,
        val deferInput: Boolean = false,
        val sampleMemory: Boolean = true,
    ) : BatchCommand
    data object Cancel : BatchCommand
}

internal data class FirstFrameStages(val page: Long, val layout: Long, val capture: Long, val write: Long) {
    companion object {
        fun measured(start: Long, sent: Long, ready: Long, captured: Long, written: Long) =
            FirstFrameStages(sent - start, ready - sent, captured - ready, written - captured)
    }
}

internal data class OutputFiles(val image: String, val metadata: String, val width: Int, val height: Int)

internal sealed interface ProducerEvent {
    data class Started(
        val total: Int, val directory: String, val pid: Int, val coldRuntime: Boolean,
        val startedAt: Long, val preparationMs: Long, val navigationAt: Long, val renderStartedAt: Long,
        val runtimeCreationMs: Long?,
    ) : ProducerEvent

    sealed interface Item : ProducerEvent {
        val index: Int
        val questionId: String
        val elapsedMs: Long
    }

    data class Written(
        override val index: Int, override val questionId: String, override val elapsedMs: Long,
        val token: String, val files: OutputFiles, val stages: FirstFrameStages, val pssKb: Long?,
    ) : Item

    data class ItemFailed(
        override val index: Int, override val questionId: String, override val elapsedMs: Long, val reason: String,
    ) : Item

    data class Phase(val name: String) : ProducerEvent
    data class Done(val succeeded: Int, val failed: Int, val cancelled: Boolean, val firstMs: Long, val elapsedMs: Long) : ProducerEvent
    data class Error(val reason: String) : ProducerEvent
}

// The wire format is also consumed by the independently compiled device probe.
internal object ProducerProtocol {
    fun command(message: Message): BatchCommand? = with(message.data) {
        when (message.what) {
            ProducerService.CANCEL -> BatchCommand.Cancel
            ProducerService.START -> BatchCommand.Start(
                getString("questionType").orEmpty(), getInt("width", 1000),
                getInt("startIndex"), getInt("limit"), getBoolean(EXTRA_DEFER_RENDER_INPUT),
                getBoolean("sampleMemory", true),
            )
            else -> null
        }
    }

    fun message(command: BatchCommand, client: Messenger) = when (command) {
        BatchCommand.Cancel -> Message.obtain(null, ProducerService.CANCEL)
        is BatchCommand.Start -> message(ProducerService.START) {
            putString("questionType", command.questionType)
            putInt("width", command.width)
            putInt("startIndex", command.startIndex)
            putInt("limit", command.limit)
            putBoolean(EXTRA_DEFER_RENDER_INPUT, command.deferInput)
            putBoolean("sampleMemory", command.sampleMemory)
        }
    }.apply { replyTo = client }

    fun message(event: ProducerEvent): Message = when (event) {
        is ProducerEvent.Started -> message(ProducerService.STARTED) {
            putInt("total", event.total)
            putString("directory", event.directory)
            putInt("rendererPid", event.pid)
            putBoolean("coldRuntime", event.coldRuntime)
            putLong("startedAt", event.startedAt)
            putLong("startupMs", event.preparationMs)
            putLong("pageNavigationAt", event.navigationAt)
            putLong("renderStartedAt", event.renderStartedAt)
            event.runtimeCreationMs?.let { putLong("runtimeCreationMs", it) }
        }
        is ProducerEvent.Written -> message(ProducerService.ITEM) {
            putItem(event)
            putString("image", event.files.image)
            putString("metadata", event.files.metadata)
            putInt("width", event.files.width)
            putInt("height", event.files.height)
            putString("token", event.token)
            putLong("pageMs", event.stages.page)
            putLong("layoutMs", event.stages.layout)
            putLong("captureMs", event.stages.capture)
            putLong("writeMs", event.stages.write)
            event.pssKb?.let { putLong("rendererPssKb", it) }
        }
        is ProducerEvent.ItemFailed -> message(ProducerService.ITEM_ERROR) {
            putItem(event)
            putString("error", event.reason)
        }
        is ProducerEvent.Phase -> message(ProducerService.PHASE) { putString("phase", event.name) }
        is ProducerEvent.Done -> message(ProducerService.DONE) {
            putInt("succeeded", event.succeeded)
            putInt("failed", event.failed)
            putBoolean("cancelled", event.cancelled)
            putLong("firstMs", event.firstMs)
            putLong("elapsedMs", event.elapsedMs)
        }
        is ProducerEvent.Error -> message(ProducerService.ERROR) { putString("error", event.reason) }
    }

    fun event(message: Message): ProducerEvent? = with(message.data) {
        when (message.what) {
            ProducerService.STARTED -> ProducerEvent.Started(
                getInt("total"), getString("directory").orEmpty(), getInt("rendererPid"), getBoolean("coldRuntime"),
                getLong("startedAt"), getLong("startupMs"), getLong("pageNavigationAt"), getLong("renderStartedAt"),
                optionalLong("runtimeCreationMs"),
            )
            ProducerService.ITEM -> ProducerEvent.Written(
                getInt("questionIndex"), getString("questionId").orEmpty(), getLong("elapsedMs"), getString("token").orEmpty(),
                OutputFiles(getString("image").orEmpty(), getString("metadata").orEmpty(), getInt("width"), getInt("height")),
                FirstFrameStages(getLong("pageMs"), getLong("layoutMs"), getLong("captureMs"), getLong("writeMs")),
                optionalLong("rendererPssKb"),
            )
            ProducerService.ITEM_ERROR -> ProducerEvent.ItemFailed(
                getInt("questionIndex"), getString("questionId").orEmpty(), getLong("elapsedMs"), getString("error").orEmpty(),
            )
            ProducerService.DONE -> ProducerEvent.Done(
                getInt("succeeded"), getInt("failed"), getBoolean("cancelled"), getLong("firstMs"), getLong("elapsedMs"),
            )
            ProducerService.ERROR -> ProducerEvent.Error(getString("error") ?: "后台产图失败")
            ProducerService.PHASE -> ProducerEvent.Phase(getString("phase").orEmpty())
            else -> null
        }
    }

    private fun Bundle.optionalLong(key: String) = if (containsKey(key)) getLong(key) else null

    private fun Bundle.putItem(item: ProducerEvent.Item) {
        putInt("questionIndex", item.index)
        putString("questionId", item.questionId)
        putLong("elapsedMs", item.elapsedMs)
        putLong("questionMs", item.elapsedMs)
    }

    private inline fun message(code: Int, encode: Bundle.() -> Unit) =
        Message.obtain(null, code).apply { data = Bundle().apply(encode) }
}

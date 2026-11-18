package dev.techlab.android.binder

internal sealed class TransferUiState(val status: String, val details: String = "") {
    data object Idle : TransferUiState("就绪")
    data class Running(val progress: String) : TransferUiState(progress)
    data object Cancelling : TransferUiState("正在取消")
    data object Cancelled : TransferUiState("测量已取消")
    data class Completed(val report: String) : TransferUiState("对照完成", report)
    data class Failed(val reason: String) : TransferUiState("测量失败", reason)

    val canRun get() = this !is Running && this != Cancelling
    val canCancel get() = this is Running
}

internal sealed interface TransferEvent {
    data class Progress(val message: String) : TransferEvent
    data class Completed(val report: String) : TransferEvent
    data class Failed(val reason: String) : TransferEvent
    data object Cancelled : TransferEvent
}

internal sealed interface TransferIntent : TransferEvent {
    data object Run : TransferIntent
    data object Cancel : TransferIntent
}

internal fun TransferUiState.reduce(event: TransferEvent): TransferUiState = when (event) {
    TransferIntent.Run -> if (canRun) TransferUiState.Running("正在连接数据进程") else this
    TransferIntent.Cancel -> if (canCancel) TransferUiState.Cancelling else this
    is TransferEvent.Progress -> if (this is TransferUiState.Running) TransferUiState.Running(event.message) else this
    is TransferEvent.Completed -> finishWith(TransferUiState.Completed(event.report))
    is TransferEvent.Failed -> finishWith(TransferUiState.Failed(event.reason))
    TransferEvent.Cancelled -> finishWith(TransferUiState.Cancelled)
}

private fun TransferUiState.finishWith(result: TransferUiState): TransferUiState = when (this) {
    TransferUiState.Cancelling -> TransferUiState.Cancelled
    is TransferUiState.Running -> result
    else -> this
}

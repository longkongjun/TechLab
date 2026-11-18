package dev.techlab.android.binder

import android.app.Application
import androidx.annotation.MainThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class TransferViewModel(app: Application) : AndroidViewModel(app) {
    private val mutableState = MutableStateFlow<TransferUiState>(TransferUiState.Idle)
    val state = mutableState.asStateFlow()
    private var study: Job? = null

    @MainThread
    fun accept(intent: TransferIntent) {
        val previous = state.value
        val next = previous.reduce(intent)
        if (next == previous) return
        mutableState.value = next
        when (intent) {
            TransferIntent.Cancel -> study?.cancel()
            TransferIntent.Run -> startStudy()
        }
    }

    private fun startStudy() {
        study = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val report = runStudy(getApplication()) { post(TransferEvent.Progress(it)) }
                post(TransferEvent.Completed(report))
            } catch (_: TimeoutCancellationException) {
                post(TransferEvent.Failed("等待数据进程超时"))
            } catch (cancelled: CancellationException) {
                post(TransferEvent.Cancelled)
                throw cancelled
            } catch (failure: Exception) {
                post(TransferEvent.Failed(failure.toString()))
            }
        }
    }

    private fun post(event: TransferEvent) = mutableState.update { it.reduce(event) }
}

package dev.techlab.android.handwriting

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ExportPage(val page: BoardPage, val marks: List<Mark>, val background: Boolean)

internal class BoardViewModel(app: Application) : AndroidViewModel(app) {
    private val drafts = BoardDrafts(app)
    private val mutableState = MutableStateFlow(BoardState())
    val state = mutableState.asStateFlow()
    private val notices = Channel<String>(Channel.BUFFERED)
    val messages = notices.receiveAsFlow()

    init {
        viewModelScope.launch {
            try {
                val draft = drafts.open()
                mutableState.value = BoardState(ready = true, page = draft.page,
                    pages = draft.sheets.mapValues { History(it.value) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = BoardState(error = "草稿读取失败：${e.localizedMessage}")
            }
        }
    }

    fun accept(intent: BoardIntent) {
        val old = mutableState.value
        if (!old.ready) return
        if (intent is BoardIntent.Export && old.exporting) return
        val next = old.reduce(intent)
        mutableState.value = next
        if (intent is BoardIntent.Export) export(ExportPage(old.page, old.history.visible, intent.background))
        if (old.page != next.page || old.pages !== next.pages) {
            val draft = BoardDraft(next.page, next.pages.mapValues { it.value.visible })
            drafts.enqueue(draft) { failure ->
                notices.trySend("草稿保存失败：${failure.localizedMessage}")
            }
        }
    }

    private fun export(snapshot: ExportPage) {
        viewModelScope.launch {
            try {
                val location = withContext(Dispatchers.IO) {
                    saveBoardPng(getApplication(), snapshot)
                }
                notices.send("已导出到 $location")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notices.send("导出失败：${e.localizedMessage}")
            } finally {
                mutableState.value = mutableState.value.copy(exporting = false)
            }
        }
    }

}

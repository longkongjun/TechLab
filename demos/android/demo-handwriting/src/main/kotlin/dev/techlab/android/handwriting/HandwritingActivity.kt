package dev.techlab.android.handwriting

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.techlab.android.handwriting.databinding.ActivityHandwritingBinding
import kotlinx.coroutines.launch

class HandwritingActivity : ComponentActivity() {
    private val model: BoardViewModel by viewModels()
    private val ui by lazy { ActivityHandwritingBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ui.root)
        ui.canvas.dispatch = model::accept
        ui.page.setOnClickListener {
            choose(R.string.board_choose, listOf(getString(R.string.board_blank), getString(R.string.board_question)),
                model.state.value.page.ordinal) { model.accept(BoardIntent.Open(BoardPage.entries[it])) }
        }
        with(ui) {
            pen.setOnClickListener { select(Tool.PEN, it) }
            highlighter.setOnClickListener { select(Tool.HIGHLIGHTER, it) }
            eraser.setOnClickListener { model.accept(BoardIntent.Select(Tool.ERASER)) }
            regionEraser.setOnClickListener { model.accept(BoardIntent.Select(Tool.REGION_ERASER)) }
            trailEraser.setOnClickListener { model.accept(BoardIntent.Select(Tool.TRAIL_ERASER)) }
            undo.setOnClickListener { model.accept(BoardIntent.Undo) }
            redo.setOnClickListener { model.accept(BoardIntent.Redo) }
            finger.setOnCheckedChangeListener { _, checked -> model.accept(BoardIntent.Finger(checked)) }
            export.setOnClickListener {
                choose(R.string.board_export, listOf(getString(R.string.board_background),
                    getString(R.string.board_transparent)), -1) { index ->
                    model.accept(BoardIntent.Export(background = index == 0))
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.state.collect(::render) }
                launch { model.messages.collect { Toast.makeText(this@HandwritingActivity, it, Toast.LENGTH_LONG).show() } }
            }
        }
    }

    override fun onPause() {
        ui.canvas.cancelGesture()
        super.onPause()
    }

    private fun select(tool: Tool, anchor: View) {
        val board = model.state.value
        if (board.tool != tool) {
            model.accept(BoardIntent.Select(tool))
            return
        }
        val style = board.style
        android.widget.PopupMenu(this, anchor).apply {
            menu.add(R.string.board_color).setOnMenuItemClickListener {
                val colors = listOf(0xff243448, 0xff1462ba, 0xffbd374b, 0xffffc928, 0xff249572).map(Long::toInt)
                choose(R.string.board_color, listOf("墨黑", "蓝色", "红色", "黄色", "绿色"),
                    colors.indexOf(style.color)) { model.accept(BoardIntent.Style(style.copy(color = colors[it]))) }
                true
            }
            menu.add(R.string.board_size).setOnMenuItemClickListener {
                val widths = if (tool == Tool.PEN) listOf(2f, 4f, 8f) else listOf(14f, 26f, 40f)
                choose(R.string.board_size, listOf("细", "中", "粗"), widths.indexOf(style.size)) {
                    model.accept(BoardIntent.Style(style.copy(size = widths[it])))
                }
                true
            }
            show()
        }
    }

    private fun choose(title: Int, choices: List<String>, current: Int, selected: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(choices.toTypedArray(), current) { dialog, index ->
                dialog.dismiss()
                selected(index)
            }.show()
    }

    private fun render(state: BoardState) = with(ui) {
        canvas.render(state)
        status.isVisible = !state.ready
        if (!state.ready) status.text = state.error ?: getString(R.string.board_loading)
        page.setText(if (state.page == BoardPage.BLANK) R.string.board_blank else R.string.board_question)
        listOf(page, finger, pen, highlighter, eraser, regionEraser, trailEraser).forEach { it.isEnabled = state.ready }
        export.available(state.ready && !state.exporting)
        finger.isChecked = state.fingerWrites
        val tools = listOf(pen to Tool.PEN, highlighter to Tool.HIGHLIGHTER,
            eraser to Tool.ERASER, regionEraser to Tool.REGION_ERASER, trailEraser to Tool.TRAIL_ERASER)
        tools.forEach { (button, tool) ->
            val selected = state.tool == tool
            if (button.isSelected != selected || button.tag == null) {
                button.isSelected = selected
                button.tag = tool
                button.background = GradientDrawable().apply {
                    cornerRadius = 9f * resources.displayMetrics.density
                    setColor(if (selected) 0xffc7e4df.toInt() else Color.TRANSPARENT)
                }
            }
        }
        undo.available(state.history.past.isNotEmpty())
        redo.available(state.history.future.isNotEmpty())
    }

    private fun Button.available(enabled: Boolean) {
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.4f
    }
}

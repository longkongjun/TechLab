package dev.techlab.android.binder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.techlab.android.binder.databinding.ActivityTransferBinding
import kotlinx.coroutines.launch

class TransferActivity : ComponentActivity() {
    private val model: TransferViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = ActivityTransferBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ui.runStudy.setOnClickListener { model.accept(TransferIntent.Run) }
        ui.cancelStudy.setOnClickListener { model.accept(TransferIntent.Cancel) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    ui.runStudy.isEnabled = state.canRun
                    ui.cancelStudy.isEnabled = state.canCancel
                    ui.status.text = state.status
                    ui.results.text = state.details
                }
            }
        }
    }
}

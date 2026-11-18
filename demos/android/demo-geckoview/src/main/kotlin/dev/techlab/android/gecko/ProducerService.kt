package dev.techlab.android.gecko

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import dev.techlab.android.gecko.producer.BatchCommand
import dev.techlab.android.gecko.producer.ProducerBatchRunner
import dev.techlab.android.gecko.producer.ProducerProtocol

class ProducerService : Service() {
    private val batches by lazy { ProducerBatchRunner(applicationContext) }
    private val commands = Messenger(Handler(Looper.getMainLooper(), ::receive))

    override fun onBind(intent: Intent): IBinder {
        if (intent.action == PRELOAD) batches.preload()
        return commands.binder
    }

    private fun receive(message: Message): Boolean {
        val client = message.replyTo ?: return false
        when (val command = ProducerProtocol.command(message)) {
            is BatchCommand.Start -> batches.start(client, command)
            BatchCommand.Cancel -> batches.cancel(client)
            null -> return false
        }
        return true
    }

    override fun onDestroy() {
        batches.close()
        super.onDestroy()
    }

    companion object {
        internal const val PRELOAD = "dev.techlab.android.gecko.PRELOAD"
        const val START = 1
        const val CANCEL = 2
        const val STARTED = 3
        const val ITEM = 4
        const val ITEM_ERROR = 5
        const val DONE = 6
        const val ERROR = 7
        const val PHASE = 10
        // The viewport defines each card's fixed-height document slot, including below the fold.
        internal const val MAX_RENDER_WIDTH = 1440
    }
}

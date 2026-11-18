package dev.techlab.android.gecko.producer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.os.RemoteException
import dev.techlab.android.gecko.ProducerService

/** Screen binding. The process-level preload connection has a separate lifetime. */
internal class ProducerClient(
    private val context: Context,
    private val onConnection: (Boolean, String?) -> Unit,
    private val onEvent: (String, ProducerEvent) -> Unit,
) : ServiceConnection {
    private var remote: Messenger? = null
    private data class Replies(val token: String, val messenger: Messenger)
    private var replies: Replies? = null
    private var bound = false

    fun bind() {
        bound = context.bindService(Intent(context, ProducerService::class.java), this, Context.BIND_AUTO_CREATE)
        if (!bound) onConnection(false, "无法连接后台渲染服务")
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        remote = Messenger(binder)
        onConnection(true, null)
    }

    override fun onServiceDisconnected(name: ComponentName) = disconnected()

    fun start(token: String, request: BatchCommand.Start) {
        val client = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (replies?.token != token) return@Handler true
            val event = ProducerProtocol.event(message) ?: return@Handler false
            if (event is ProducerEvent.Done || event is ProducerEvent.Error) replies = null
            onEvent(token, event)
            true
        })
        replies = Replies(token, client)
        send(request, client)
    }

    fun cancel() {
        val client = replies?.messenger ?: return
        replies = null
        send(BatchCommand.Cancel, client)
    }

    private fun send(command: BatchCommand, client: Messenger) {
        val service = remote ?: return disconnected()
        try {
            service.send(ProducerProtocol.message(command, client))
        } catch (_: RemoteException) {
            disconnected()
        }
    }

    private fun disconnected() {
        remote = null
        replies = null
        onConnection(false, "后台渲染服务已断开")
    }

    fun close() {
        cancel()
        if (bound) context.unbindService(this)
        bound = false
        remote = null
    }
}

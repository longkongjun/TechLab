package dev.techlab.android.gecko.engine

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import org.json.JSONObject

/** Main-thread owner of one resident document and its current bridge request. */
internal class RenderDocument(
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val deadline: (Runnable?) -> Unit = handshakeDeadline(),
    private val log: (String) -> Unit = { Log.i("TechLabPage", "pid=${android.os.Process.myPid()} $it") },
) {
    private sealed interface State {
        data object Loading : State
        data class Ready(val preparationMs: Long, val send: (JSONObject) -> Unit) : State
        data class Failed(val reason: String, val incompatible: Boolean) : State
        data object Closed : State
    }

    private var state: State = State.Loading
    val isReady get() = state is State.Ready
    val reusable get() = state == State.Loading || isReady
    val failure get() = (state as? State.Failed)?.reason
    val incompatible get() = (state as? State.Failed)?.incompatible == true
    val preparationMs get() = (state as? State.Ready)?.preparationMs
    private val createdAt = now()
    private var preparingAt = createdAt
    private var firstNavigationAt = 0L
    var navigationAt = 0L
        private set
    var generation = 0L
        private set
    var url = PageRenderer.RESIDENT_URL
        private set
    var onState: () -> Unit = {}
    var receive: (JSONObject) -> Unit = {}
    private var activeToken: String? = null
    private val timeout = Runnable { fail("等待 Pages 渲染脚本或消息桥就绪超时") }

    init { deadline(timeout) }

    fun navigating() {
        check(state != State.Closed)
        state = State.Loading
        activeToken = null
        navigationAt = now()
        if (generation == 0L) firstNavigationAt = navigationAt else preparingAt = navigationAt
        generation++
        deadline(timeout)
    }

    // A request arriving during preloading owns the remaining wait, even if navigation has not begun.
    fun renderStart(requestedAt: Long): Long =
        if (createdAt < requestedAt) requestedAt else maxOf(requestedAt, firstNavigationAt)

    fun checkCompatibility(address: String, reload: () -> Unit) {
        if (state != State.Loading || address != url) return
        if (url == PageRenderer.RESIDENT_URL) {
            url = "${PageRenderer.RESIDENT_URL}&refresh=${UUID.randomUUID()}"
            log("页面缺少常驻协议，重新获取 HTML")
            reload()
        } else {
            fail("已加载的 Pages 不支持常驻渲染，请确认 Pages 发布完成后重试", incompatible = true)
        }
    }

    fun restartNavigation() {
        url = PageRenderer.RESIDENT_URL
    }

    fun ready(deliver: (JSONObject) -> Unit) {
        if (state != State.Loading) return
        deadline(null)
        state = State.Ready(now() - preparingAt, deliver)
        log("instance=$createdAt doc=$navigationAt readyMs=$preparationMs")
        onState()
    }

    fun setQuestions(input: JSONObject, defer: Boolean) {
        val bridge = checkNotNull(state as? State.Ready) { "页面尚未就绪" }
        val token = input.getString("token")
        if (token == activeToken) return
        activeToken = token
        bridge.send(JSONObject().put("type", "setQuestions").put("input", input).put("defer", defer))
    }

    fun message(data: JSONObject) {
        if (isReady && activeToken != null && activeToken == data.optString("token")) receive(data)
    }

    fun clear() {
        val token = activeToken ?: return
        activeToken = null
        try {
            (state as? State.Ready)?.send?.invoke(JSONObject().put("type", "clear").put("token", token))
        } catch (error: Exception) {
            fail(error.toString())
        }
    }

    fun fail(reason: String, incompatible: Boolean = false) {
        if (!reusable) return
        deadline(null)
        activeToken = null
        state = State.Failed(reason, incompatible)
        log("doc=$createdAt $reason")
        onState()
    }

    fun detach() {
        onState = {}
        receive = {}
        clear()
    }

    fun close() {
        state = State.Closed
        activeToken = null
        onState = {}
        receive = {}
        deadline(null)
    }
}

private fun handshakeDeadline(): (Runnable?) -> Unit {
    val handler = Handler(Looper.getMainLooper())
    return { timeout ->
        handler.removeCallbacksAndMessages(null)
        if (timeout != null) handler.postDelayed(timeout, 30_000)
    }
}

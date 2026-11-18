package dev.techlab.android.gecko.engine

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError

internal class GeckoPage(renderer: PageRenderer) {
    val document = RenderDocument()
    val session = GeckoSession()
    var onComposite: () -> Unit = {}
    private var port: WebExtension.Port? = null
    private var closed = false
    private var contentLost = false
    val canReload get() = !closed && !contentLost && session.isOpen &&
        document.navigationAt != 0L && document.failure != null

    init {
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFirstComposite(session: GeckoSession) = onComposite()
            override fun onKill(session: GeckoSession) = lost("Gecko 内容进程被终止", true)
            override fun onCrash(session: GeckoSession) = lost("Gecko 内容进程崩溃", true)
        }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                if (uri == document.url) lost("Pages 页面加载失败")
                return null
            }
        }
        try {
            session.open(renderer.runtime)
            session.setActive(true)
            renderer.ensureBridge().accept({ installed ->
                if (!closed && session.isOpen && installed != null) {
                    session.webExtensionController.setMessageDelegate(installed, delegate(), "techlab_renderer")
                    document.navigating()
                    session.loadUri(document.url)
                } else if (!closed) lost("Gecko 页面或消息桥不可用")
            }, { error -> lost(error.toString()) })
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    private fun delegate() = object : WebExtension.MessageDelegate {
        override fun onConnect(connection: WebExtension.Port) {
            val sender = connection.sender
            if (closed || sender.session !== session || !sender.isTopLevel || sender.url != document.url || port != null) {
                connection.disconnect()
                return
            }
            port = connection
            connection.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    if (closed || this@GeckoPage.port !== port || message !is String) return
                    if (message == "ready") {
                        document.ready { command -> port.postMessage(JSONObject().put("json", command.toString())) }
                    } else if (message == "unsupported") {
                        document.checkCompatibility(sender.url, ::navigate)
                    } else {
                        val data = runCatching { JSONObject(message) }.getOrNull() ?: return
                        document.message(data)
                    }
                }
                override fun onDisconnect(port: WebExtension.Port) {
                    if (!closed && this@GeckoPage.port === port) lost("Gecko 页面消息通道已断开")
                }
            })
        }
    }

    fun reload() {
        check(canReload)
        document.restartNavigation()
        navigate()
    }

    private fun navigate() {
        val old = port
        port = null
        old?.disconnect()
        document.navigating()
        session.loadUri(document.url)
    }

    private fun lost(reason: String, fatal: Boolean = false) {
        if (fatal) contentLost = true
        val generation = document.generation
        Handler(Looper.getMainLooper()).post {
            if (fatal || document.generation == generation) document.fail(reason)
        }
    }

    fun close() {
        closed = true
        document.close()
        onComposite = {}
        port?.disconnect()
        port = null
        if (session.isOpen) session.close()
    }
}

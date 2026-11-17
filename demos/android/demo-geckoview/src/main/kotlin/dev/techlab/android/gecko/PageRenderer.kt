package dev.techlab.android.gecko

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import java.io.File

/** Native owns the catalog and the messaging bridge to the hosted renderer. */
internal class PageRenderer private constructor(app: Context, profileName: String) {
    val catalog = QuestionCatalog(app)
    var runtimeCreationMs: Long? = null
        private set

    private val runtimeState = lazy {
        // Both processes can hold a Gecko runtime at once, so their profiles need separate locks.
        val profile = File(app.filesDir, "gecko/$profileName")
        check(profile.mkdirs() || profile.isDirectory) { "无法创建 Gecko 配置目录" }
        // Gecko's GPU child service is package-wide. Keep compositing inside each isolated host.
        val config = File(profile, "runtime.yaml")
        config.writeText(buildString {
            appendLine("prefs:")
            appendLine("  layers.gpu-process.enabled: false")
            if (profileName == "producer") appendLine("  dom.ipc.processPrelaunch.enabled: false")
        })
        val settings = GeckoRuntimeSettings.Builder()
            .configFilePath(config.absolutePath)
            .arguments(arrayOf("-profile", profile.absolutePath))
            // Dynamic Android content services avoid sharing fixed process slots with the other runtime.
            .isolatedProcessEnabled(Build.VERSION.SDK_INT >= 29)
            .build()
        // Time the create call itself, excluding profile setup and asynchronous session readiness.
        val startedAt = SystemClock.elapsedRealtime()
        GeckoRuntime.create(app, settings).also {
            runtimeCreationMs = SystemClock.elapsedRealtime() - startedAt
        }
    }
    val runtime by runtimeState
    private var extension: GeckoResult<WebExtension>? = null

    private fun ensureBridge(): GeckoResult<WebExtension> = extension ?: runtime.webExtensionController.ensureBuiltIn(
        "resource://android/assets/gecko/bridge/", "renderer@techlab.dev"
    ).also { pending ->
        extension = pending
        pending.accept({}, { if (extension === pending) extension = null })
    }

    fun url(token: String): String = "$PAGE_URL?token=$token"

    fun attach(
        session: GeckoSession,
        input: (String) -> JSONObject?,
        receive: (JSONObject) -> Unit,
        ready: () -> Unit,
        fail: (Throwable) -> Unit,
    ) {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                if (uri != null && isRenderPage(uri)) {
                    val token = Uri.parse(uri).getQueryParameter("token")
                    receive(JSONObject().put("type", "error").put("token", token).put("message", "Pages 页面加载失败"))
                }
                return null
            }
        }
        ensureBridge().accept({ installed ->
            if (!session.isOpen) return@accept
            if (installed == null) {
                fail(IllegalStateException("Gecko 没有返回渲染消息桥"))
                return@accept
            }
            session.webExtensionController.setMessageDelegate(installed, object : WebExtension.MessageDelegate {
                override fun onMessage(
                    nativeApp: String,
                    message: Any,
                    sender: WebExtension.MessageSender,
                ): GeckoResult<Any>? {
                    if (sender.session !== session || !sender.isTopLevel || !isRenderPage(sender.url) || message !is JSONObject) {
                        return null
                    }
                    if (message.optString("type") == "request-input") {
                        // Gecko's event dispatcher accepts a string reply for the JSON payload.
                        return input(message.optString("token"))?.let { GeckoResult.fromValue<Any>(it.toString()) }
                    }
                    receive(message)
                    return null
                }
            }, "techlab_renderer")
            ready()
        }, { error -> if (error != null) fail(error) })
    }

    companion object {
        const val ORIGIN = "https://longkongjun.github.io"
        private const val PAGE_URL = "$ORIGIN/TechLab/geckoview/render/index.html"

        private var singleton: PageRenderer? = null
        val isInitialized get() = singleton?.runtimeState?.isInitialized() == true

        fun isRenderPage(url: String?): Boolean = url?.substringBefore('?')?.substringBefore('#') == PAGE_URL

        fun get(context: Context, profileName: String): PageRenderer =
            singleton ?: PageRenderer(context.applicationContext, profileName).also { singleton = it }
    }
}

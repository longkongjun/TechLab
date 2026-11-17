package dev.techlab.android.gecko

import android.content.Context
import android.os.Build
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

internal class SystemWebPreview(
    context: Context,
    layers: BooleanArray,
    input: (String) -> JSONObject?,
    receive: (JSONObject) -> Unit,
    onLost: () -> Unit,
) {
    val frame = FrameLayout(context)
    private val web: WebView
    private val overlay: GeometryOverlay
    private var pixelRatio = 0f
    private var token: String? = null
    private var closed = false

    init {
        if (Build.VERSION.SDK_INT >= 28 && !dataDirectoryConfigured) {
            WebView.setDataDirectorySuffix("techlab-renderer")
            dataDirectoryConfigured = true
        }
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            "系统 WebView 不支持本地消息桥"
        }
        web = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.useWideViewPort = true
            settings.textZoom = 100
            settings.setSupportZoom(false)
        }
        overlay = GeometryOverlay(context, layers) { matrix ->
            if (pixelRatio <= 0f) {
                false
            } else {
                matrix.setScale(pixelRatio, pixelRatio)
                matrix.postTranslate(-web.scrollX.toFloat(), -web.scrollY.toFloat())
                true
            }
        }
        web.setOnScrollChangeListener { _, _, _, _, _ -> overlay.postInvalidateOnAnimation() }
        web.webViewClient = object : WebViewClient() {
            // The native loader selects the page. A fixture cannot navigate the comparison view.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                pageError(request, error.description.toString())
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, error: WebResourceResponse) {
                pageError(request, "HTTP ${error.statusCode}")
            }

            private fun pageError(request: WebResourceRequest, message: String) {
                if (!request.isForMainFrame) return
                val failedToken = request.url.getQueryParameter("token") ?: return
                receive(JSONObject().put("type", "error").put("token", failedToken).put("message", message))
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (!closed) onLost()
                return true
            }
        }
        WebViewCompat.addWebMessageListener(web, "techLabWebView", setOf(PageRenderer.ORIGIN)) { view, message, _, mainFrame, reply ->
            if (!mainFrame || !PageRenderer.isRenderPage(view.url)) return@addWebMessageListener
            val data = runCatching { JSONObject(message.data ?: return@addWebMessageListener) }.getOrNull()
                ?: return@addWebMessageListener
            val requestToken = data.optString("token")
            if (token != requestToken) return@addWebMessageListener
            if (data.optString("type") == "request-input") {
                input(requestToken)?.let { reply.postMessage(it.toString()) }
            } else {
                receive(data)
            }
        }
        frame.addView(web, FrameLayout.LayoutParams(-1, -1))
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
    }

    fun load(url: String, requestToken: String) {
        token = requestToken
        overlay.display(null)
        web.loadUrl(url)
    }

    fun stop() {
        token = null
        web.stopLoading()
        web.loadUrl("about:blank")
    }

    fun display(data: JSONObject) {
        pixelRatio = data.optDouble("pixelRatio").toFloat()
        if (pixelRatio.isFinite() && pixelRatio > 0f) overlay.display(data.optJSONArray("geometry"))
    }

    fun invalidateOverlay() = overlay.postInvalidateOnAnimation()

    fun close() {
        if (closed) return
        closed = true
        token = null
        frame.removeView(web)
        web.destroy()
    }

    private companion object {
        var dataDirectoryConfigured = false
    }
}

package dev.techlab.android.gecko.engine

import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.techlab.android.gecko.databinding.ResidentWebPreviewBinding
import org.json.JSONObject

internal class SystemWebPreview(context: Context) {
    val document = RenderDocument()
    private val binding: ResidentWebPreviewBinding
    val frame get() = binding.root
    private val web get() = binding.web
    private val overlay get() = binding.overlay
    val layers get() = overlay.layers
    private var pixelRatio = 0f
    private var closed = false

    init {
        if (Build.VERSION.SDK_INT >= 28 && !dataDirectoryConfigured) {
            WebView.setDataDirectorySuffix("techlab-renderer")
            dataDirectoryConfigured = true
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            error("系统 WebView 不支持本地消息桥")
        }
        binding = ResidentWebPreviewBinding.inflate(LayoutInflater.from(context))
        web.apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.useWideViewPort = true
            settings.textZoom = 100
            settings.setSupportZoom(false)
        }
        overlay.pageToView = { matrix ->
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

            override fun onPageFinished(view: WebView, url: String) {
                if (closed || url != document.url || document.isReady) return
                // A legacy renderer exports render(), but never installs the resident message handler.
                view.evaluateJavascript(
                    "typeof window.TechLabRenderer?.render === 'function' && typeof window.techLabWebView?.onmessage !== 'function'",
                ) { legacy ->
                    if (legacy == "true") document.checkCompatibility(url, ::navigate)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                pageError(request, error.description.toString())
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, error: WebResourceResponse) {
                pageError(request, "HTTP ${error.statusCode}")
            }

            private fun pageError(request: WebResourceRequest, message: String) {
                if (!request.isForMainFrame) return
                if (request.url.toString() == document.url) document.fail(message)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (!closed) {
                    document.fail("WebView 渲染进程已退出")
                    close()
                }
                return true
            }
        }
        WebViewCompat.addWebMessageListener(web, "techLabWebView", setOf(PageRenderer.ORIGIN)) { view, message, _, mainFrame, reply ->
            if (!mainFrame) return@addWebMessageListener
            val data = runCatching { JSONObject(message.data ?: return@addWebMessageListener) }.getOrNull()
                ?: return@addWebMessageListener
            if (closed || view.url != document.url) return@addWebMessageListener
            if (data.optString("type") == "ready") {
                document.ready { reply.postMessage(it.toString()) }
            } else document.message(data)
        }
        navigate()
    }

    private fun navigate() {
        document.navigating()
        web.loadUrl(document.url)
    }

    fun clearOverlay() = overlay.display(null)

    fun display(data: JSONObject) {
        pixelRatio = data.optDouble("pixelRatio").toFloat()
        if (pixelRatio.isFinite() && pixelRatio > 0f) overlay.display(data.optJSONArray("geometry"))
    }

    fun invalidateOverlay() = overlay.postInvalidateOnAnimation()

    fun close() {
        if (closed) return
        closed = true
        document.close()
        frame.removeView(web)
        web.destroy()
    }

    private companion object {
        var dataDirectoryConfigured = false
    }
}

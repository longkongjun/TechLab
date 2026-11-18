package dev.techlab.android.gecko

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.annotation.MainThread
import dev.techlab.android.gecko.engine.GeckoPage
import dev.techlab.android.gecko.engine.PageRenderer
import dev.techlab.android.gecko.engine.SystemWebPreview

/** Holds application-context previews until an Activity takes exclusive ownership. */
@MainThread
object RenderWarmup {
    const val EXTRA_ENABLED = "preloadRendererPages"
    private var started = false
    private val gecko = IdlePreview<GeckoPage>({ it.document.reusable && it.session.isOpen }, GeckoPage::close)
    private val web = IdlePreview<SystemWebPreview>({ it.document.reusable }, SystemWebPreview::close)

    // Intentionally bound for the main process lifetime, including gaps between Activities.
    private val producerConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) = Unit
        override fun onServiceDisconnected(name: ComponentName) = Unit
    }

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        preload("后台") {
            val intent = Intent(app, ProducerService::class.java).setAction(ProducerService.PRELOAD)
            if (!app.bindService(intent, producerConnection, Context.BIND_AUTO_CREATE)) {
                Log.w("TechLabPage", "无法连接后台预加载服务")
            }
        }
        preload("Gecko") { gecko.warm { GeckoPage(PageRenderer.preview(app)) } }
        preload("WebView") { web.warm { SystemWebPreview(app) } }
    }

    internal fun takeGecko(renderer: PageRenderer, fresh: Boolean) = gecko.take(fresh) { GeckoPage(renderer) }
    internal fun takeWeb(context: Context, fresh: Boolean) = web.take(fresh) { SystemWebPreview(context.applicationContext) }
    internal fun recycle(page: GeckoPage) = gecko.recycle(page)
    internal fun recycle(page: SystemWebPreview) = web.recycle(page)

    private inline fun preload(name: String, action: () -> Unit) {
        try {
            action()
        } catch (failure: Exception) {
            Log.w("TechLabPage", "${name}预加载失败", failure)
        }
    }
}

/** A late launcher preload must not create a second page after the first screen has taken one. */
internal class IdlePreview<T : Any>(private val reusable: (T) -> Boolean, private val close: (T) -> Unit) {
    private var requested = false
    private var idle: T? = null

    fun warm(create: () -> T) {
        if (!requested && idle == null) idle = create()
    }

    fun take(fresh: Boolean, create: () -> T): T {
        requested = true
        val page = idle
        idle = null
        if (page != null) {
            if (!fresh && reusable(page)) return page
            close(page)
        }
        return create()
    }

    fun recycle(page: T) {
        if (idle == null && reusable(page)) idle = page else close(page)
    }
}

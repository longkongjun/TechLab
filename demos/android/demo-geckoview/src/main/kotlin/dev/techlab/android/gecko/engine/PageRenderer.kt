package dev.techlab.android.gecko.engine

import android.content.Context
import android.os.Build
import android.os.SystemClock
import dev.techlab.android.gecko.QuestionCatalog
import java.io.File
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension

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

    internal fun ensureBridge(): GeckoResult<WebExtension> = extension ?: runtime.webExtensionController.ensureBuiltIn(
        "resource://android/assets/gecko/bridge/", "renderer@techlab.dev"
    ).also { pending ->
        extension = pending
        pending.accept({}, { if (extension === pending) extension = null })
    }

    companion object {
        const val ORIGIN = "https://longkongjun.github.io"
        private const val PAGE_URL = "$ORIGIN/TechLab/geckoview/render/index.html"

        // New APKs must not reuse the HTML cached before the resident protocol was deployed.
        const val RESIDENT_URL = "$PAGE_URL?resident=1&renderer=20261118.3"

        private var singleton: PageRenderer? = null
        val isInitialized get() = singleton?.runtimeState?.isInitialized() == true

        fun preview(context: Context) = get(context, "preview")
        fun producer(context: Context) = get(context, "producer")

        private fun get(context: Context, profileName: String): PageRenderer =
            singleton ?: PageRenderer(context.applicationContext, profileName).also { singleton = it }
    }
}

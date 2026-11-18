package dev.techlab.android.gecko.producer

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import dev.techlab.android.gecko.ProducerService
import dev.techlab.android.gecko.engine.GeckoPage
import dev.techlab.android.gecko.engine.PageRenderer
import org.mozilla.geckoview.GeckoDisplay

/** The display, surface and session are acquired and released together, independently of batches. */
internal class OffscreenPage private constructor(
    val page: GeckoPage,
    val display: GeckoDisplay,
    private val reader: ImageReader,
) {
    private var closed = false

    fun close() {
        if (closed) return
        closed = true
        page.document.onState = {}
        display.surfaceDestroyed()
        page.session.releaseDisplay(display)
        reader.setOnImageAvailableListener(null, null)
        reader.close()
        page.close()
    }

    companion object {
        fun open(renderer: PageRenderer, handler: Handler): OffscreenPage {
            val page = GeckoPage(renderer)
            var display: GeckoDisplay? = null
            var reader: ImageReader? = null
            try {
                val width = ProducerService.MAX_RENDER_WIDTH
                val height = 3200
                display = page.session.acquireDisplay()
                reader = ImageReader.newInstance(width, height, ImageFormat.PRIVATE, 2)
                val resident = OffscreenPage(page, display, reader)
                reader.setOnImageAvailableListener({ source ->
                    if (!resident.closed) {
                        try {
                            source.acquireLatestImage()?.close()
                        } catch (error: RuntimeException) {
                            page.document.fail("离屏 Surface 缓冲失败：${error.message}")
                        }
                    }
                }, handler)
                display.surfaceChanged(GeckoDisplay.SurfaceInfo.Builder(reader.surface).size(width, height).build())
                return resident
            } catch (error: Exception) {
                display?.let(page.session::releaseDisplay)
                reader?.close()
                page.close()
                throw error
            }
        }
    }
}

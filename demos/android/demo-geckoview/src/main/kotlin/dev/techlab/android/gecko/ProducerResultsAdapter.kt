package dev.techlab.android.gecko

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal class ProducerResultsAdapter(private val layers: BooleanArray) :
    RecyclerView.Adapter<ProducerResultsAdapter.Holder>() {
    private val rows = mutableListOf<Bundle>()
    private val decoder = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun append(result: Bundle) {
        rows.add(Bundle(result))
        notifyItemInserted(rows.lastIndex)
    }

    fun clear() {
        val count = rows.size
        rows.clear()
        notifyItemRangeRemoved(0, count)
    }

    fun refreshLayers() = notifyItemRangeChanged(0, itemCount, LAYERS)

    fun close() {
        decoder.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val padding = (context.resources.displayMetrics.density * 10).toInt()
        val header = TextView(context).apply { setPadding(padding, padding, padding, padding) }
        val preview = ResultImage(context, layers)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(-1, -2)
            setPadding(0, 0, 0, padding)
            addView(header, LinearLayout.LayoutParams(-1, -2))
            addView(preview, LinearLayout.LayoutParams(-1, -2))
        }
        return Holder(row, header, preview)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.cancelLoad()
        val row = rows[position]
        val number = row.getInt("questionIndex") + 1
        val caption = "$number. ${row.getString("questionId").orEmpty()} · 批次 +${row.getLong("questionMs")} ms"
        val error = row.getString("error")
        if (error != null) {
            holder.header.text = "$caption · 失败：$error"
            holder.preview.prepare(0, 0)
            return
        }
        val width = row.getInt("width")
        val height = row.getInt("height")
        holder.header.text = "$caption · ${width}×$height"
        holder.preview.prepare(width, height)
        val ticket = holder.ticket
        val viewportWidth = holder.itemView.resources.displayMetrics.widthPixels
        holder.load = decoder.submit {
            // Load only rows RecyclerView binds. Coordinate scaling uses the original PNG dimensions.
            val decoded = runCatching {
                var sample = 1
                while (width / (sample * 2) >= viewportWidth.coerceAtLeast(1)) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val image = checkNotNull(BitmapFactory.decodeFile(row.getString("image"), options))
                val geometry = JSONObject(File(checkNotNull(row.getString("metadata"))).readText()).getJSONArray("geometry")
                image to GeometryRectangles(geometry)
            }
            main.post {
                // Scrolling can rebind the holder while the previous file is being decoded.
                if (holder.ticket != ticket || decoder.isShutdown) return@post
                decoded.onSuccess { (image, geometry) -> holder.preview.display(image, geometry) }
                    .onFailure {
                        holder.header.text = "$caption · 读取产物失败"
                        holder.preview.prepare(0, 0)
                    }
            }
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it === LAYERS }) holder.preview.invalidate()
        else onBindViewHolder(holder, position)
    }

    override fun onViewRecycled(holder: Holder) {
        holder.cancelLoad()
        holder.preview.prepare(0, 0)
        super.onViewRecycled(holder)
    }

    internal class Holder(row: View, val header: TextView, val preview: ResultImage) : RecyclerView.ViewHolder(row) {
        var ticket = 0
        var load: Future<*>? = null

        fun cancelLoad() {
            ticket++
            load?.cancel(true)
            load = null
        }
    }

    private companion object { val LAYERS = Any() }
}

internal class ResultImage(context: Context, private val layers: BooleanArray) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val imageToView = Matrix()
    private val drawingArea = Rect()
    private var imageWidth = 0
    private var imageHeight = 0
    private var bitmap: Bitmap? = null
    private var rectangles: GeometryRectangles? = null

    fun prepare(width: Int, height: Int) {
        imageWidth = width
        imageHeight = height
        bitmap = null
        rectangles = null
        requestLayout()
        invalidate()
    }

    fun display(image: Bitmap, geometry: GeometryRectangles) {
        bitmap = image
        rectangles = geometry
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val height = if (imageWidth > 0) (minOf(width, imageWidth).toFloat() * imageHeight / imageWidth).toInt() else 0
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val image = bitmap ?: return
        val drawnWidth = minOf(width, imageWidth)
        val left = (width - drawnWidth) / 2
        drawingArea.set(left, 0, left + drawnWidth, height)
        canvas.drawBitmap(image, null, drawingArea, paint)
        imageToView.setScale(drawnWidth.toFloat() / imageWidth, height.toFloat() / imageHeight)
        imageToView.postTranslate(left.toFloat(), 0f)
        rectangles?.draw(canvas, imageToView, layers, width, height)
    }
}

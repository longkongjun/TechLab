package dev.techlab.android.gecko.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.techlab.android.gecko.R
import dev.techlab.android.gecko.GeometryRectangles
import dev.techlab.android.gecko.databinding.ProducerResultBinding
import dev.techlab.android.gecko.producer.ProducerEvent
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import org.json.JSONObject

internal class ProducerResultsAdapter(private val layers: BooleanArray) :
    ListAdapter<ProducerEvent.Item, ProducerResultsAdapter.Holder>(Items) {
    private val decoder = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun refreshLayers() = notifyItemRangeChanged(0, itemCount, LAYERS)

    fun close() {
        decoder.shutdownNow()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val row = ProducerResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        row.preview.layers = layers
        return Holder(row)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.cancelLoad()
        val row = getItem(position)
        val context = holder.itemView.context
        val caption = context.getString(R.string.result_caption, row.index + 1, row.questionId, row.elapsedMs)
        when (row) {
            is ProducerEvent.ItemFailed -> {
                holder.header.text = context.getString(R.string.result_error, caption, row.reason)
                holder.preview.prepare(0, 0)
                return
            }
            is ProducerEvent.Written -> Unit
        }
        val (imagePath, metadataPath, width, height) = row.files
        holder.header.text = context.getString(R.string.result_size, caption, width, height)
        holder.preview.prepare(width, height)
        val ticket = holder.ticket
        val viewportWidth = holder.itemView.resources.displayMetrics.widthPixels
        holder.load = decoder.submit {
            // Load only rows RecyclerView binds. Coordinate scaling uses the original PNG dimensions.
            val decoded = runCatching {
                var sample = 1
                while (width / (sample * 2) >= viewportWidth.coerceAtLeast(1)) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val geometry = GeometryRectangles(JSONObject(File(metadataPath).readText()).getJSONArray("geometry"))
                val bitmap = checkNotNull(BitmapFactory.decodeFile(imagePath, options))
                bitmap to geometry
            }
            main.post {
                // Scrolling can rebind the holder while the previous file is being decoded.
                if (holder.ticket != ticket || decoder.isShutdown) {
                    decoded.getOrNull()?.first?.recycle()
                    return@post
                }
                decoded.onSuccess { (image, geometry) -> holder.preview.display(image, geometry) }
                    .onFailure {
                        holder.header.text = context.getString(R.string.result_unreadable, caption)
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

    internal class Holder(row: ProducerResultBinding) : RecyclerView.ViewHolder(row.root) {
        val header = row.header
        val preview = row.preview
        var ticket = 0
        var load: Future<*>? = null

        fun cancelLoad() {
            ticket++
            load?.cancel(true)
            load = null
        }
    }

    private object Items : DiffUtil.ItemCallback<ProducerEvent.Item>() {
        override fun areItemsTheSame(old: ProducerEvent.Item, new: ProducerEvent.Item) = old.questionId == new.questionId
        override fun areContentsTheSame(old: ProducerEvent.Item, new: ProducerEvent.Item) = old == new
    }

    private companion object { val LAYERS = Any() }
}

internal class ResultImage(context: Context, attrs: AttributeSet) : View(context, attrs) {
    var layers = BooleanArray(4)
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

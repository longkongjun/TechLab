package dev.techlab.android.binder

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Random
import java.util.zip.CRC32

internal const val BLOCK = 32 * 1024
internal val binarySizes = mapOf("bin32k" to BLOCK, "bin1m" to 1024 * 1024, "bin8m" to 8 * 1024 * 1024)
internal val questions = listOf("Q001", "Q002", "Q003")
internal const val LIVE_QUESTION = "Q002"
internal fun markerColor(id: String) = Color.rgb(160, 20 + questions.indexOf(id) * 50, 90)

internal data class Fixture(val file: File, val meta: Bundle)

/** Only fixed IDs can reach the filesystem. Files stay immutable for the life of the service. */
internal class Fixtures(private val directory: File) {
    private val ready = mutableMapOf<String, Fixture>()
    private var encoderPixels: Bitmap? = null

    fun encodeQuestion(output: OutputStream) {
        get(LIVE_QUESTION)
        if (!checkNotNull(encoderPixels).compress(Bitmap.CompressFormat.PNG, 100, output)) {
            throw IOException("输入样本编码未完成")
        }
    }

    @Synchronized
    fun get(id: String): Fixture = ready.getOrPut(id) {
        require(id in questions || id in binarySizes)
        directory.mkdirs()
        val file = File(directory, id)
        val meta = Bundle().apply { putString("id", id) }
        val random = Random(id.hashCode().toLong())
        binarySizes[id]?.let { size ->
            file.outputStream().use { out ->
                val block = ByteArray(BLOCK)
                repeat(size / BLOCK) { random.nextBytes(block); out.write(block) }
            }
        } ?: writeQuestion(file, id, random, meta)
        val checksum = CRC32()
        file.inputStream().use { input ->
            val block = ByteArray(BLOCK)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                checksum.update(block, 0, count)
            }
        }
        meta.putInt("length", file.length().toInt())
        meta.putLong("crc32", checksum.value)
        Fixture(file, meta)
    }

    private fun writeQuestion(file: File, id: String, random: Random, meta: Bundle) {
        val page = createBitmap(896, 640)
        try {
            val canvas = Canvas(page)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 36f; color = Color.BLACK }
            canvas.drawText("$id    Find the marked point", 28f, 56f, paint)
            val row = IntArray(page.width)
            for (y in 96 until page.height) {
                for (x in row.indices) row[x] = Color.BLACK or random.nextInt(0x1000000)
                page.setPixels(row, 0, row.size, 0, y, row.size, 1)
            }
            val x = 64 + 140 * questions.indexOf(id)
            val y = 148 + 60 * questions.indexOf(id)
            paint.color = markerColor(id)
            canvas.drawRect(x.toFloat(), y.toFloat(), x + 20f, y + 20f, paint)
            meta.putInt("x", x)
            meta.putInt("y", y)
            meta.putInt("width", page.width)
            meta.putInt("height", page.height)
            file.outputStream().use { check(page.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            if (id == LIVE_QUESTION) encoderPixels = page.copy(Bitmap.Config.ARGB_8888, false)
        } finally { page.recycle() }
    }
}

package dev.techlab.android.handwriting

import android.content.Context
import android.util.AtomicFile
import androidx.ink.geometry.ImmutableVec
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

internal data class BoardDraft(val page: BoardPage, val sheets: Map<BoardPage, List<Mark>>)

internal class BoardDrafts(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "handwriting-v1.board"))

    suspend fun open(): BoardDraft = withContext(dispatcher) { load() }

    fun enqueue(draft: BoardDraft, onFailure: (Exception) -> Unit) {
        // All instances use the same file. Reopening queues behind prior writes, even after back.
        // A slow disk only needs the most recent full snapshot, not every intermediate edit.
        pending.set {
            try {
                save(draft)
            } catch (failure: Exception) {
                onFailure(failure)
            }
        }
        worker.execute { pending.getAndSet(null)?.invoke() }
    }

    fun load(): BoardDraft {
        val stream = try {
            file.openRead()
        } catch (e: FileNotFoundException) {
            if (file.baseFile.exists()) throw e
            return BoardDraft(BoardPage.BLANK, emptyMap())
        }
        val length = stream.channel.size()
        return DataInputStream(stream.buffered()).use { input ->
            val version = input.readInt()
            require(version in 1..3) { "不支持的画板版本" }
            val current = BoardPage.valueOf(input.readUTF())
            val sheets = BoardPage.entries.associateWith {
                val count = input.readInt()
                require(count in 0..length / 14) { "笔画数量无效" }
                List(count) {
                    val style = PenStyle(Tool.valueOf(input.readUTF()), input.readInt(), input.readFloat())
                    val size = input.readInt()
                    require(size in 1..length) { "笔迹长度无效" }
                    val bytes = ByteArray(size).also(input::readFully)
                    val batch = bytes.inputStream().use { StrokeInputBatch.decode(it) }
                    val stroke = Stroke(style.brush(), batch)
                    val clip = if (version == 1) null else InkClip.restore(stroke, input.readAreas(version))
                    Mark(style, stroke, clip)
                }
            }
            BoardDraft(current, sheets)
        }
    }

    fun save(draft: BoardDraft) {
        val stream = file.startWrite()
        try {
            val out = DataOutputStream(stream.buffered())
            out.writeInt(3)
            out.writeUTF(draft.page.name)
            BoardPage.entries.forEach { page ->
                val marks = draft.sheets[page].orEmpty()
                out.writeInt(marks.size)
                marks.forEach { (style, stroke, clip) ->
                    out.writeUTF(style.tool.name)
                    out.writeInt(style.color)
                    out.writeFloat(style.size)
                    val encoded = ByteArrayOutputStream().also(stroke.inputs::encode)
                    out.writeInt(encoded.size())
                    encoded.writeTo(out)
                    val areas = clip?.areas.orEmpty()
                    out.writeInt(areas.size)
                    areas.forEach { out.writeArea(it) }
                }
            }
            out.flush()
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw IOException("草稿写入失败", failure)
        }
    }

    private fun DataOutputStream.writeArea(area: EraseArea) {
        fun point(p: ImmutableVec) { writeFloat(p.x); writeFloat(p.y) }
        when (area) {
            is EraserSweep -> {
                writeByte(TRAIL)
                point(area.from)
                point(area.to)
                writeFloat(area.radius)
            }
            is EnclosedArea -> {
                writeByte(REGION)
                writeInt(area.vertices.size)
                area.vertices.forEach(::point)
            }
        }
    }

    private fun DataInputStream.readAreas(version: Int): List<EraseArea> {
        val count = readInt()
        require(count in 0..available() / (5 * Float.SIZE_BYTES)) { "擦除轨迹数量无效" }
        fun coordinate() = readFloat().also { require(it.isFinite()) { "擦除坐标无效" } }
        fun point() = ImmutableVec(coordinate(), coordinate())
        return List(count) {
            when (if (version == 2) TRAIL else readUnsignedByte()) {
                TRAIL -> {
                    val from = point()
                    val to = point()
                    val radius = coordinate()
                    require(radius > 0f) { "擦除半径无效" }
                    EraserSweep(from, to, radius)
                }
                REGION -> {
                    val vertices = readInt()
                    require(vertices in 3..available() / (2 * Float.SIZE_BYTES)) { "圈选顶点数量无效" }
                    EnclosedArea(List(vertices) { point() })
                }
                else -> throw IOException("不支持的擦除方式")
            }
        }
    }

    private companion object {
        const val TRAIL = 1
        const val REGION = 2
        val worker = Executors.newSingleThreadExecutor()
        val dispatcher = worker.asCoroutineDispatcher()
        val pending = AtomicReference<(() -> Unit)?>(null)
    }
}

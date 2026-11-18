package dev.techlab.android.binder

import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import androidx.core.graphics.get
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

internal suspend fun functionalChecks(source: ITransferSource): JSONObject {
    val delivered = JSONArray()
    for (route in listOf(FileFdTransfer, ReliablePipeTransfer)) {
        // Request each page only after the preceding one has decoded with its own metadata.
        for (id in questions) {
            val meta = source.describe(id)
            check(meta.getString("id") == id)
            val out = ByteArrayOutputStream(meta.getInt("length"))
            val timing = measureTransfer(source, TransferInput.StoredFile(id), meta, route, 0, out)
            val bytes = out.toByteArray()
            val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                check(bitmap.width == meta.getInt("width") && bitmap.height == meta.getInt("height"))
                check(bitmap[meta.getInt("x") + 5, meta.getInt("y") + 5] == markerColor(id))
            } finally { bitmap.recycle() }
            delivered.put(timing.json().put("x", meta.getInt("x")).put("y", meta.getInt("y"))
                .put("width", meta.getInt("width")).put("height", meta.getInt("height")))
        }
    }
    val before = source.diagnostics()
    val localBefore = openFdCount()
    repeat(4) {
        // Abandon both a regular file and a blocked/active pipe reader after a single byte.
        for (route in listOf(FileFdTransfer, ReliablePipeTransfer)) {
            val descriptor = route.open(source, TransferInput.StoredFile("bin8m"))
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { check(it.read() >= 0) }
        }
        val failedAsExpected = try {
            val descriptor = source.openPipe("bin8m", true)
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val block = ByteArray(BLOCK)
                while (input.read(block) >= 0) { /* Drain partial bytes before checking the error. */ }
                descriptor.checkError()
            }
            false
        } catch (failure: IOException) {
            failure.message.orEmpty().contains("injected writer failure")
        }
        check(failedAsExpected) { "可靠管道没有报告生产端错误" }
    }
    val after = withTimeout(5000) {
        var state = source.diagnostics()
        while (state.getInt("active") != 0) { delay(10); state = source.diagnostics() }
        state
    }
    val localAfter = openFdCount()
    check(after.getInt("broken") - before.getInt("broken") == 8)
    check(localAfter <= localBefore + 1 && after.getInt("fd") <= before.getInt("fd") + 1) {
        "异常路径 FD 持续增长：$localBefore/$localAfter, ${before.getInt("fd")}/${after.getInt("fd")}"
    }
    return JSONObject().put("pages", delivered).put("faultRounds", 4)
        .put("writerErrors", after.getInt("broken") - before.getInt("broken"))
        .put("writersRemaining", after.getInt("active")).put("clientFdBefore", localBefore)
        .put("clientFdAfter", localAfter).put("sourceFdBefore", before.getInt("fd")).put("sourceFdAfter", after.getInt("fd"))
}

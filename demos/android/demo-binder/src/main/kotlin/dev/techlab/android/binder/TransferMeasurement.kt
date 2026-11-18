package dev.techlab.android.binder

import android.os.Bundle
import android.os.SystemClock.elapsedRealtimeNanos
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

internal data class Timing(
    val id: String,
    val bytes: Int,
    val strategy: TransferStrategy,
    val round: Int,
    val firstNs: Long,
    val totalNs: Long,
    val crc: Long,
) {
    fun json() = JSONObject().put("id", id).put("bytes", bytes).put("route", strategy.id)
        .put("round", round).put("firstNs", firstNs).put("totalNs", totalNs).put("crc32", crc)
}

internal suspend fun measureTransfer(
    source: ITransferSource,
    input: TransferInput,
    meta: Bundle,
    strategy: TransferStrategy,
    round: Int,
    copy: ByteArrayOutputStream? = null,
): Timing {
    val request = TransferRequest(input, meta.getInt("length"))
    require(request.length > 0)
    val expected = meta.getLong("crc32")
    val context = currentCoroutineContext()
    val buffer = ByteArray(BLOCK)
    val checksum = CRC32()
    var consumed = 0
    var first = 0L
    val start = elapsedRealtimeNanos()
    strategy.transfer(source, request, buffer) { bytes, count ->
        context.ensureActive()
        consumed += count
        if (first == 0L && consumed >= minOf(BLOCK, request.length)) first = elapsedRealtimeNanos()
        checksum.update(bytes, 0, count)
        copy?.write(bytes, 0, count)
    }
    check(consumed == request.length && checksum.value == expected) { "长度或 CRC32 不符：${input.displayName} ${strategy.id}" }
    return Timing(input.reportId, consumed, strategy, round, first - start, elapsedRealtimeNanos() - start, checksum.value)
}

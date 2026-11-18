package dev.techlab.android.binder

import android.os.Build
import android.os.ParcelFileDescriptor

internal sealed class TransferInput(val sourceId: String, val reportId: String = sourceId) {
    val displayName: String
        get() = when (this) {
            FreshPng -> "按需编码"
            is StoredFile -> if (id in questions) "预生成编码文件" else id
        }

    data class StoredFile(val id: String) : TransferInput(id)
    data object FreshPng : TransferInput(LIVE_QUESTION, "liveQ002")
}

internal data class TransferRequest(val input: TransferInput, val length: Int)

internal typealias ChunkConsumer = (bytes: ByteArray, count: Int) -> Unit

internal interface TransferStrategy {
    val id: String
    val title: String

    fun supports(input: TransferInput): Boolean = input is TransferInput.StoredFile

    /**
     * Deliver chunks in order on the study's IO thread. Each callback borrows its bytes until
     * it returns. Strategies may reuse [buffer] and must release resources if [consume] throws.
     */
    fun transfer(source: ITransferSource, request: TransferRequest, buffer: ByteArray, consume: ChunkConsumer)
}

/** The registration order also controls rotation during measurement and display in the report. */
internal fun availableStrategies(): List<TransferStrategy> = buildList {
    add(BinderChunkTransfer)
    add(FileFdTransfer)
    add(ReliablePipeTransfer)
    if (Build.VERSION.SDK_INT >= 27) add(SharedMemoryTransfer)
}.also { strategies ->
    require(strategies.distinctBy { it.id }.size == strategies.size) { "传输方案 ID 重复" }
}

internal interface DescriptorTransfer : TransferStrategy {
    fun open(source: ITransferSource, input: TransferInput): ParcelFileDescriptor

    override fun transfer(source: ITransferSource, request: TransferRequest, buffer: ByteArray, consume: ChunkConsumer) {
        val fd = open(source, request.input)
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                consume(buffer, count)
            }
            if (fd.canDetectErrors()) fd.checkError()
        }
    }
}

package dev.techlab.android.binder

import java.io.File
import java.io.RandomAccessFile

internal object BinderChunkTransfer : TransferStrategy {
    override val id = "BINDER"
    override val title = "Binder 数组 / 分块"

    override fun transfer(source: ITransferSource, request: TransferRequest, buffer: ByteArray, consume: ChunkConsumer) {
        var offset = 0
        while (offset < request.length) {
            val count = minOf(buffer.size, request.length - offset)
            val chunk = source.readChunk(request.input.sourceId, offset, count)
            check(chunk.size == count)
            consume(chunk, count)
            offset += count
        }
    }

    fun readChunk(file: File, offset: Int, length: Int): ByteArray {
        require(offset >= 0 && length in 1..BLOCK)
        return RandomAccessFile(file, "r").use { input ->
            require(offset.toLong() + length <= input.length())
            input.seek(offset.toLong())
            ByteArray(length).also(input::readFully)
        }
    }
}

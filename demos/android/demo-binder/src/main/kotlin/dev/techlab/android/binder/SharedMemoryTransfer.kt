package dev.techlab.android.binder

import android.os.Bundle
import android.os.SharedMemory
import android.system.OsConstants
import androidx.annotation.RequiresApi
import java.io.File

@RequiresApi(27)
internal object SharedMemoryTransfer : TransferStrategy {
    override val id = "SHARED"
    override val title = "共享内存"

    override fun transfer(source: ITransferSource, request: TransferRequest, buffer: ByteArray, consume: ChunkConsumer) {
        SharedMemory.create("techlab-transfer", request.length).use { memory ->
            source.fillRegion(request.input.sourceId, Bundle().apply { putParcelable("memory", memory) })
            check(memory.setProtect(OsConstants.PROT_READ))
            val mapped = memory.mapReadOnly()
            try {
                while (mapped.hasRemaining()) {
                    val count = minOf(buffer.size, mapped.remaining())
                    mapped.get(buffer, 0, count)
                    consume(buffer, count)
                }
            } finally {
                SharedMemory.unmap(mapped)
            }
        }
    }

    @Suppress("DEPRECATION")
    fun fill(file: File, args: Bundle) {
        requireNotNull(args.getParcelable<SharedMemory>("memory")).use { memory ->
            require(memory.size.toLong() == file.length())
            val mapped = memory.mapReadWrite()
            try {
                file.inputStream().channel.use { input ->
                    while (mapped.hasRemaining()) check(input.read(mapped) > 0)
                }
            } finally {
                SharedMemory.unmap(mapped)
            }
        }
    }
}

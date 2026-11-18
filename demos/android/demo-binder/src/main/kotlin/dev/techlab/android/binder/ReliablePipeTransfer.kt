package dev.techlab.android.binder

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal object ReliablePipeTransfer : DescriptorTransfer {
    override val id = "PIPE"
    override val title = "可靠管道"
    override fun supports(input: TransferInput) = true

    override fun open(source: ITransferSource, input: TransferInput): ParcelFileDescriptor = when (input) {
        is TransferInput.StoredFile -> source.openPipe(input.sourceId, false)
        TransferInput.FreshPng -> source.encodeQuestion(true)
    }
}

/** Service-owned writers outlive the Binder call that hands the reader to the client. */
internal class PipeWriters : Closeable {
    private val pool = Executors.newFixedThreadPool(2)
    private val active = AtomicInteger()
    private val broken = AtomicInteger()
    val activeCount get() = active.get()
    val errorCount get() = broken.get()

    fun openFile(file: File, interruptWriter: Boolean) = open { output ->
        file.inputStream().use { input ->
            if (interruptWriter) {
                val prefix = ByteArray(4096)
                DataInputStream(input).readFully(prefix)
                output.write(prefix)
                throw IOException("injected writer failure")
            }
            input.copyTo(output, BLOCK)
        }
    }

    fun open(write: (OutputStream) -> Unit): ParcelFileDescriptor {
        val (reader, writer) = ParcelFileDescriptor.createReliablePipe()
        active.incrementAndGet()
        try {
            pool.execute {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(writer).use { output ->
                        try {
                            write(output)
                        } catch (failure: Exception) {
                            broken.incrementAndGet()
                            writer.closeWithError(failure.message ?: "pipe write failed")
                        }
                    }
                } finally {
                    active.decrementAndGet()
                }
            }
        } catch (failure: RuntimeException) {
            active.decrementAndGet()
            reader.close()
            writer.close()
            throw failure
        }
        return reader
    }

    override fun close() = pool.shutdown()
}

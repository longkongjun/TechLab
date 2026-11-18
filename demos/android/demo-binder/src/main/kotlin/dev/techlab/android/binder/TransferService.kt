package dev.techlab.android.binder

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File

internal fun openFdCount() = File("/proc/self/fd").list()?.size ?: -1

class TransferService : Service() {
    private val fixtures by lazy { Fixtures(File(cacheDir, "binder-fixtures-v1")) }
    private val writers = PipeWriters()

    private val source = object : ITransferSource.Stub() {
        override fun describe(id: String) = Bundle(fixtures.get(id).meta)

        override fun readChunk(id: String, offset: Int, length: Int) =
            BinderChunkTransfer.readChunk(fixtures.get(id).file, offset, length)

        override fun openFile(id: String) = FileFdTransfer.openReadOnly(fixtures.get(id).file)

        override fun openPipe(id: String, interruptWriter: Boolean) =
            writers.openFile(fixtures.get(id).file, interruptWriter)

        override fun encodeQuestion(stream: Boolean): ParcelFileDescriptor =
            if (stream) writers.open(fixtures::encodeQuestion)
            else FileFdTransfer.encodeToFile(cacheDir, fixtures::encodeQuestion)

        override fun fillRegion(id: String, region: Bundle) {
            if (Build.VERSION.SDK_INT < 27) throw UnsupportedOperationException("共享内存需要 API 27")
            SharedMemoryTransfer.fill(fixtures.get(id).file, region)
        }

        override fun diagnostics() = Bundle().apply {
            putInt("pid", Process.myPid())
            putInt("fd", openFdCount())
            putInt("active", writers.activeCount)
            putInt("broken", writers.errorCount)
        }
    }

    override fun onBind(intent: Intent) = source

    override fun onDestroy() {
        writers.close()
        super.onDestroy()
    }
}

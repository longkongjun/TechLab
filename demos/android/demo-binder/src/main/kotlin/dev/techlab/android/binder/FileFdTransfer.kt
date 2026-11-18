package dev.techlab.android.binder

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.OutputStream

internal object FileFdTransfer : DescriptorTransfer {
    override val id = "FILE"
    override val title = "只读文件 FD"
    override fun supports(input: TransferInput) = true

    override fun open(source: ITransferSource, input: TransferInput): ParcelFileDescriptor = when (input) {
        is TransferInput.StoredFile -> source.openFile(input.sourceId)
        TransferInput.FreshPng -> source.encodeQuestion(false)
    }

    fun openReadOnly(file: File): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    fun encodeToFile(directory: File, encode: (OutputStream) -> Unit): ParcelFileDescriptor {
        val temp = File.createTempFile("binder-page-", ".png", directory)
        try {
            temp.outputStream().use(encode)
            return openReadOnly(temp)
        } finally {
            // The returned descriptor keeps the inode alive after unlink.
            temp.delete()
        }
    }
}

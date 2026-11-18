package dev.techlab.android.gecko.producer

import android.graphics.Bitmap
import android.graphics.Rect
import java.io.File
import java.util.Locale
import org.json.JSONObject

/** Called on the serial writer; owns the captured bitmap even when writing fails. */
internal fun writeArtifacts(
    directory: File, index: Int, questionId: String, frame: Bitmap, crop: Rect, metadata: JSONObject,
): Result<OutputFiles> {
    val stem = "%03d-%s".format(Locale.ROOT, index + 1, questionId)
    val png = File(directory, "$stem.png")
    val json = File(directory, "$stem.json")
    var cropped: Bitmap? = null
    return try {
        cropped = Bitmap.createBitmap(frame, crop.left, crop.top, crop.width(), crop.height())
        png.outputStream().use { check(cropped.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        json.writeText(metadata.toString(2))
        Result.success(OutputFiles(png.absolutePath, json.absolutePath, crop.width(), crop.height()))
    } catch (error: Exception) {
        png.delete()
        json.delete()
        Result.failure(error)
    } finally {
        if (cropped !== frame) cropped?.recycle()
        frame.recycle()
    }
}

internal fun OutputFiles.deleteArtifacts() {
    File(image).delete()
    File(metadata).delete()
}

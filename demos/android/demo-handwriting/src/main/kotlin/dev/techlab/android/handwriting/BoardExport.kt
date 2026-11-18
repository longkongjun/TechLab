package dev.techlab.android.handwriting

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.util.UUID

internal fun saveBoardPng(context: Context, snapshot: ExportPage): String {
    val layer = if (snapshot.background) "page" else "ink"
    val name = "${snapshot.page.name.lowercase()}-${layer}-${UUID.randomUUID()}.png"
    val folder = "${Environment.DIRECTORY_PICTURES}/TechLab"
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: throw IOException("图片目录不可用")
        val dir = File(base, "TechLab")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建图片目录")
        val file = File(dir, name)
        try {
            file.outputStream().use { BoardPaper.writePng(snapshot, it) }
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
        return file.absolutePath
    }
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, folder)
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("无法创建图片")
    try {
        val stream = resolver.openOutputStream(uri) ?: throw IOException("无法打开图片")
        stream.use { BoardPaper.writePng(snapshot, it) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        if (resolver.update(uri, values, null, null) != 1) throw IOException("无法完成图片写入")
    } catch (failure: Exception) {
        resolver.delete(uri, null, null)
        throw failure
    }
    return "$folder/$name"
}

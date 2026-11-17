package dev.techlab.android.gecko

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.items(key: String): List<JSONObject> = getJSONArray(key).objects()
internal fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

internal fun JSONObject.toRect(): RectF {
    val x = getDouble("x").toFloat()
    val y = getDouble("y").toFloat()
    return RectF(x, y, x + getDouble("width").toFloat(), y + getDouble("height").toFloat())
}

internal fun JSONObject.mapToSurface(matrix: Matrix): RectF = toRect().also { matrix.mapRect(it) }

internal fun JSONArray.toImageGeometry(matrix: Matrix, crop: Rect): JSONArray {
    // Clip in the capture's pixel space before rebasing the boxes to the exported PNG origin.
    fun transform(value: JSONObject): JSONObject? {
        val rect = value.getJSONObject("rect")
        val pixels = rect.mapToSurface(matrix)
        if (!pixels.intersect(crop.left.toFloat(), crop.top.toFloat(), crop.right.toFloat(), crop.bottom.toFloat())) {
            return null
        }
        val baseLine = value.optDouble("baseLine")
        if (baseLine.isFinite()) {
            val point = floatArrayOf(rect.getDouble("x").toFloat(), baseLine.toFloat())
            matrix.mapPoints(point)
            if (point[1] >= crop.top && point[1] < crop.bottom) {
                value.put("baseLine", point[1] - crop.top)
            } else {
                value.remove("baseLine")
            }
        }
        return value.put("rect", JSONObject()
            .put("x", pixels.left - crop.left).put("y", pixels.top - crop.top)
            .put("width", pixels.width()).put("height", pixels.height()))
    }
    return JSONArray(objects().mapNotNull { question ->
        val clipped = transform(question) ?: return@mapNotNull null
        for (key in listOf("lines", "checks")) {
            clipped.put(key, JSONArray(clipped.items(key).mapNotNull(::transform)))
        }
        clipped
    })
}

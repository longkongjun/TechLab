package dev.techlab.android

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import dev.techlab.android.gecko.EXTRA_DEFER_RENDER_INPUT
import dev.techlab.android.gecko.ProducerService
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Starts only the bound service and checks outputs as each question becomes available. */
class ProducerProbe : Instrumentation() {
    private val args = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        arguments?.let(args::putAll)
        start()
    }

    override fun onStart() {
        try {
            repeat(args.getString("repeat")?.toInt()?.coerceIn(1, 5) ?: 1) { testBatch() }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\nPASS\n") })
        } catch (failure: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "\nFAIL: ${failure.stackTraceToString()}") })
        }
    }

    private fun testBatch() {
        val connected = LinkedBlockingQueue<Messenger>()
        val events = LinkedBlockingQueue<Pair<Int, Bundle>>()
        val client = Messenger(Handler(Looper.getMainLooper()) { message ->
            events.add(message.what to Bundle(message.data))
            true
        })
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected.add(Messenger(binder))
            }

            override fun onServiceDisconnected(name: ComponentName) {
                events.add(ProducerService.ERROR to Bundle().apply { putString("error", "Service disconnected") })
            }
        }
        val app = targetContext
        check(app.bindService(Intent(app, ProducerService::class.java), conn, Context.BIND_AUTO_CREATE))
        try {
            val producer = checkNotNull(connected.poll(15, TimeUnit.SECONDS)) { "Service not bound" }
            val questionType = args.getString("questionType") ?: "MENTAL_ARITHMETIC"
            val limit = args.getString("limit")?.toInt() ?: 3
            val offset = args.getString("start")?.toInt() ?: 0
            val expectedFirst = args.getString("firstQuestion")
            var previousPage: Long? = null
            val widths = listOfNotNull(args.getString("width")?.toInt() ?: 1000, args.getString("secondWidth")?.toInt())
            for (width in widths) {
                val captured = mutableListOf<Long>()
                val indices = mutableSetOf<Int>()
                val failures = mutableListOf<String>()
                var peak = 0L
                producer.send(Message.obtain(null, ProducerService.START).apply {
                    replyTo = client
                    data = Bundle().apply {
                        putString("questionType", questionType)
                        putBoolean(EXTRA_DEFER_RENDER_INPUT, args.getString(EXTRA_DEFER_RENDER_INPUT).toBoolean())
                        putInt("limit", limit)
                        putInt("startIndex", offset)
                        putInt("width", width)
                    }
                })
                var total = 0
                results@ while (true) {
                    val (type, data) = checkNotNull(events.poll(40, TimeUnit.SECONDS)) { "Timed out waiting for result" }
                    when (type) {
                        ProducerService.STARTED -> {
                            check(data.getInt("rendererPid") != Process.myPid()) { "Renderer must be a distinct process" }
                            val page = data.getLong("pageNavigationAt")
                            check(page > 0 && (previousPage == null || previousPage == page)) { "Batch navigated to another page" }
                            previousPage = page
                            total = data.getInt("total")
                            print("START $questionType total=$total rendererPid=${data.getInt("rendererPid")} cold=${data.getBoolean("coldRuntime")}")
                        }
                        ProducerService.ITEM -> {
                            val id = data.getString("questionId")!!
                            val index = data.getInt("questionIndex")
                            check(index in offset until offset + total && indices.add(index))
                            if (index == offset && expectedFirst != null) check(id == expectedFirst)
                            validateQuestion(data, questionType)
                            val elapsed = data.getLong("elapsedMs", -1)
                            check(elapsed >= 0 && elapsed == data.getLong("questionMs", -1))
                            peak = maxOf(peak, data.getLong("rendererPssKb"))
                            captured += elapsed
                            print("ITEM id=$id index=$index ${data.getInt("width")}x${data.getInt("height")} " +
                                "time=$elapsed image=${data.getString("image")}")
                        }
                        ProducerService.ITEM_ERROR -> failures += "${data.getInt("questionIndex")}: ${data.getString("error")}"
                        ProducerService.DONE -> {
                            check(failures.isEmpty()) { failures.joinToString() }
                            check(captured.size == total && data.getInt("succeeded") == total)
                            check(captured.first() == data.getLong("firstMs"))
                            check(captured.max() <= data.getLong("elapsedMs"))
                            if (total > 1) check(captured.first() < data.getLong("elapsedMs")) { "First result was not streamed" }
                            print("DONE count=$total first=${captured.first()}ms total=${data.getLong("elapsedMs")}ms sampledRendererPss=${peak}KB")
                            break@results
                        }
                        ProducerService.ERROR -> error(data.getString("error").orEmpty())
                    }
                }
            }
        } finally {
            app.unbindService(conn)
        }
    }

    private fun validateQuestion(result: Bundle, questionType: String) {
        val image = checkNotNull(BitmapFactory.decodeFile(result.getString("image")))
        try {
            val metadata = JSONObject(File(result.getString("metadata")!!).readText())
            check(metadata.getString("questionType") == questionType)
            check(metadata.getString("questionId") == result.getString("questionId"))
            check(metadata.getInt("questionIndex") == result.getInt("questionIndex"))
            check(metadata.getInt("width") == image.width && metadata.getInt("height") == image.height)
            val questions = metadata.getJSONArray("geometry")
            check(questions.length() > 0)
            if (questionType == "MENTAL_ARITHMETIC") {
                val checks = questions.getJSONObject(0).getJSONArray("checks")
                check(checks.length() == 1 && checks.getJSONObject(0).getString("source") == "arithmetic-square")
            }
            for (i in 0 until questions.length()) {
                val question = questions.getJSONObject(i)
                validateRect(question.getJSONObject("rect"), image.width, image.height)
                for (group in listOf("lines", "checks")) {
                    val boxes = question.getJSONArray(group)
                    for (j in 0 until boxes.length()) {
                        val box = boxes.getJSONObject(j)
                        val rect = box.getJSONObject("rect")
                        validateRect(rect, image.width, image.height)
                        if (group == "lines") {
                            val kinds = box.getJSONArray("kinds")
                            if (kinds.length() == 1 && kinds.getString(0) == "image") {
                                check(!box.has("baseLine"))
                            } else {
                                val baseline = box.getDouble("baseLine")
                                val top = rect.getDouble("y")
                                check(baseline >= top - 1 && baseline <= top + rect.getDouble("height") + 1) {
                                    "Baseline outside its rendered line: $box"
                                }
                            }
                        }
                    }
                }
            }
            var present = false
            for (y in 0 until image.height step 3) {
                for (x in 0 until image.width step 3) {
                    val color = image.getPixel(x, y)
                    if (android.graphics.Color.alpha(color) > 0 &&
                        android.graphics.Color.red(color) < 180 && android.graphics.Color.green(color) < 180) {
                        present = true
                        break
                    }
                }
                if (present) break
            }
            check(present) { "Question image appears blank" }
        } finally {
            image.recycle()
        }
    }

    private fun validateRect(rect: JSONObject, width: Int, height: Int) {
        val x = rect.getDouble("x")
        val y = rect.getDouble("y")
        val w = rect.getDouble("width")
        val h = rect.getDouble("height")
        check(x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= width + 0.01 && y + h <= height + 0.01) {
            "Out-of-bounds geometry: $rect in ${width}x$height"
        }
    }

    private fun print(line: String) = sendStatus(1, Bundle().apply { putString("stream", "\n$line\n") })
}

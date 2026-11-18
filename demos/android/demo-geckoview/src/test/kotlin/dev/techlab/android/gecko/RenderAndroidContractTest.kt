package dev.techlab.android.gecko

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import dev.techlab.android.gecko.databinding.ActivityGeckoDemoBinding
import dev.techlab.android.gecko.databinding.ResidentWebPreviewBinding
import dev.techlab.android.gecko.databinding.ProducerResultBinding
import dev.techlab.android.gecko.producer.BatchCommand
import dev.techlab.android.gecko.producer.FirstFrameStages
import dev.techlab.android.gecko.producer.OutputFiles
import dev.techlab.android.gecko.producer.ProducerEvent
import dev.techlab.android.gecko.producer.ProducerProtocol
import dev.techlab.android.gecko.producer.deleteArtifacts
import dev.techlab.android.gecko.producer.writeArtifacts
import dev.techlab.android.gecko.ui.Mode
import dev.techlab.android.gecko.ui.RenderTiming
import dev.techlab.android.gecko.ui.restoreRenderScreen
import dev.techlab.android.gecko.ui.saveTo
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RenderAndroidContractTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun xmlBindsCustomViewsAndAllThreeModeContainers() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val binding = ActivityGeckoDemoBinding.inflate(activity.layoutInflater)
        val item = ProducerResultBinding.inflate(activity.layoutInflater, binding.results, false)
        assertTrue(binding.geckoOverlay.parent === binding.geckoFrame)
        assertTrue(binding.producerPanel === binding.results.parent)
        assertNotNull(binding.webFrame)
        assertNotNull(item.preview)
        val resident = ResidentWebPreviewBinding.inflate(activity.layoutInflater)
        assertSame(resident.root, resident.web.parent)
        assertSame(resident.root, resident.overlay.parent)
    }

    @Test fun startCancelAndLegacyRequestDefaultsSurviveMessengerEncoding() {
        val client = Messenger(Handler(Looper.getMainLooper()))
        val request = BatchCommand.Start("CLOZE", 720, 2, 3, deferInput = true, sampleMemory = false)
        val message = ProducerProtocol.message(request, client)
        assertEquals(client.binder, message.replyTo.binder)
        assertEquals(request, ProducerProtocol.command(message))
        assertEquals(BatchCommand.Cancel, ProducerProtocol.command(ProducerProtocol.message(BatchCommand.Cancel, client)))
        val legacy = Message.obtain(null, ProducerService.START).apply { data = Bundle().apply { putString("questionType", "CLOZE") } }
        assertEquals(BatchCommand.Start("CLOZE", 1000), ProducerProtocol.command(legacy))
    }

    @Test fun allBatchEventsKeepTheirWireCodesAndDiagnosticFields() {
        val stages = FirstFrameStages(5, 6, 7, 8)
        val item = ProducerEvent.Written(2, "q3", 100, "attempt", OutputFiles("/png", "/json", 720, 48), stages, 128L)
        val cases = listOf(
            3 to ProducerEvent.Started(1, "/files", 12, true, 10, 15, 20, 20, 7L),
            4 to item,
            5 to ProducerEvent.ItemFailed(3, "q4", 125, "failed"),
            6 to ProducerEvent.Done(1, 1, false, 100, 150),
            7 to ProducerEvent.Error("oops"),
            10 to ProducerEvent.Phase("排版"),
        )
        for ((code, event) in cases) {
            val message = ProducerProtocol.message(event)
            assertEquals(code, message.what)
            assertEquals(event, ProducerProtocol.event(message))
        }
        val payload = ProducerProtocol.message(item.copy(pssKb = null)).data
        assertFalse(payload.containsKey("rendererPssKb"))
        assertEquals(payload.getLong("elapsedMs"), payload.getLong("questionMs"))
        assertEquals("/png", payload.getString("image"))
    }

    @Test fun restoresNumericHistoryAndReadsOlderSavedScreens() {
        val old = Bundle().apply {
            putString("mode", "PRODUCER")
            putBooleanArray("layers", booleanArrayOf(true, false, false, true))
            putString("renderTiming.PRODUCER", "旧计时\n分段")
        }.restoreRenderScreen()
        assertEquals(9, old.layers)
        assertEquals("旧计时 · 上次", old.timingLine(Mode.PRODUCER, 0))
        val timing = RenderTiming(21, 33, 87, "公式降级 1")
        val current = old.copy(question = QuestionType.CLOZE, fresh = true, history = mapOf(Mode.GECKO to timing))
        val restored = Bundle().also(current::saveTo).restoreRenderScreen()
        assertEquals(current, restored)
    }

    @Test fun filesAreReportedTogetherAndStaleCleanupIsScopedToItsAttempt() {
        val bitmap = Bitmap.createBitmap(6, 4, Bitmap.Config.ARGB_8888)
        val dir = temp.newFolder("batch")
        val retry = File(dir, "retry").apply { mkdir() }
        val output = writeArtifacts(dir, 0, "q", bitmap, Rect(0, 0, 6, 4), JSONObject()).getOrThrow()
        val retried = writeArtifacts(retry, 0, "q", Bitmap.createBitmap(6, 4, Bitmap.Config.ARGB_8888),
            Rect(0, 0, 6, 4), JSONObject()).getOrThrow()
        assertTrue(bitmap.isRecycled)
        assertTrue(File(output.image).isFile && File(output.metadata).isFile)
        output.deleteArtifacts()
        assertFalse(File(output.image).exists() || File(output.metadata).exists())
        assertTrue(File(retried.image).isFile && File(retried.metadata).isFile)
    }

    @Test fun partialWriteDiscardsThePngAndReleasesItsBitmap() {
        val dir = temp.newFolder("batch")
        File(dir, "001-q.json").mkdir()
        val frame = Bitmap.createBitmap(6, 4, Bitmap.Config.ARGB_8888)
        assertTrue(writeArtifacts(dir, 0, "q", frame, Rect(0, 0, 5, 3), JSONObject()).isFailure)
        assertFalse(File(dir, "001-q.png").exists())
        assertTrue(frame.isRecycled)
    }
}

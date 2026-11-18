package dev.techlab.android.gecko

import dev.techlab.android.gecko.producer.FirstFrameStages
import dev.techlab.android.gecko.producer.OutputFiles
import dev.techlab.android.gecko.producer.ProducerEvent
import dev.techlab.android.gecko.ui.Mode
import dev.techlab.android.gecko.ui.RenderAction
import dev.techlab.android.gecko.ui.RenderTiming
import dev.techlab.android.gecko.ui.RenderViewModel
import dev.techlab.android.gecko.ui.RenderWork
import dev.techlab.android.gecko.ui.TypeOption
import org.junit.Assert.*
import org.junit.Test

class RenderViewModelTest {
    private val gecko = Mode.GECKO
    private val producer = Mode.PRODUCER
    private val options = listOf(TypeOption(QuestionType.SINGLE_CHOICE, 26), TypeOption(QuestionType.FILL_IN, 3))
    private val model = RenderViewModel().apply { attach(options, null) }
    private val screen get() = model.state.value

    @Test fun earlyRequestIsClaimedOnceAndStableFirstGeometryCanCompleteIt() {
        model.start(gecko, "one", 110)
        model.pageState(gecko, null)
        assertFalse(screen.canRetest)
        model.pageState(gecko, 80)
        assertNotNull(model.previewInput(gecko, 110, 160))
        assertNull(model.previewInput(gecko, 110, 180))
        model.geometry(gecko, "old", 200, true, "")
        assertNull(screen.runs[gecko]?.firstMs)
        model.geometry(gecko, "one", 210, true, "")
        assertEquals(RenderTiming(80, 100, 100), screen.completedTiming(gecko))
        assertTrue(screen.canRetest)
        model.detach()
        assertTrue(screen.timingLine(gecko, 400).endsWith("上次"))
    }

    @Test fun provisionalFirstGeometryIsTimedSeparatelyFromCompletion() {
        model.start(gecko, "one", 100)
        model.previewInput(gecko, 140, 150)
        model.geometry(gecko, "one", 190, false, "")
        model.geometry(gecko, "one", 290, true, "图片失败 1")
        assertEquals(RenderTiming(null, 50, 150, "图片失败 1"), screen.completedTiming(gecko))
    }

    @Test fun switchingModesCancelsOnlyActiveWorkAndHonorsExplicitNewInstance() {
        model.dispatch(RenderAction.SetFresh(true))
        model.start(gecko, "preview", 100)
        assertEquals(listOf(RenderWork.Cancel(gecko), RenderWork.Load()), model.dispatch(RenderAction.SelectMode(producer)))
        assertNull(screen.runs[gecko])
        model.connection(true)
        assertEquals(listOf(RenderWork.Load(false)), model.dispatch(RenderAction.Retest))
        model.dispatch(RenderAction.SelectMode(gecko))
        assertEquals(listOf(RenderWork.Load(true)), model.dispatch(RenderAction.Retest))
    }

    @Test fun batchFirstWaitsForFilesAndStaleRepliesCannotUpdateNewBatch() {
        model.dispatch(RenderAction.SelectMode(producer))
        model.connection(true)
        model.start(producer, "a", 90)
        val started = ProducerEvent.Started(26, "/tmp", 2, true, 100, 40, 120, 120, null)
        model.producerEvent("a", started)
        model.producerEvent("a", ProducerEvent.ItemFailed(0, "q1", 42, "bad"))
        assertNull(screen.runs[producer]?.firstMs)
        val stages = FirstFrameStages.measured(120, 150, 165, 175, 190)
        val files = ProducerEvent.Written(1, "q2", 90, "attempt", OutputFiles("png", "json", 80, 20), stages, null)
        model.producerEvent("a", files)
        assertEquals(70L, screen.runs[producer]?.firstMs)
        assertEquals(70, stages.page + stages.layout + stages.capture + stages.write)
        model.producerEvent("a", ProducerEvent.Done(1, 1, false, 90, 180))
        assertEquals(RenderTiming(40, 70, 160), screen.completedTiming(producer))
        model.start(producer, "b", 300)
        model.producerEvent("a", files)
        assertTrue(screen.batch!!.rows.isEmpty())
        model.producerEvent("b", ProducerEvent.Done(0, 0, true, -1, 4))
        assertEquals("已取消", screen.runs[producer]?.error)
    }

    @Test fun changingTypeClearsCompletedResultsAndIgnoresPreviousToken() {
        model.start(gecko, "old", 100)
        model.geometry(gecko, "old", 110, true, "")
        model.dispatch(RenderAction.SelectType(QuestionType.FILL_IN))
        model.start(gecko, "new", 115)
        model.geometry(gecko, "old", 120, true, "")
        assertTrue(screen.runs.getValue(gecko).running)
        assertEquals(3, screen.selected!!.count)
    }

    @Test fun recreatedActivityPrefersTheRetainedStateOverAnEarlierSavedBundle() {
        val snapshot = screen
        model.dispatch(RenderAction.SelectType(QuestionType.FILL_IN))
        model.start(gecko, "one", 100)
        model.geometry(gecko, "one", 160, true, "")
        model.detach()
        model.attach(options, snapshot)
        assertEquals(QuestionType.FILL_IN, screen.question)
        assertEquals(60L, screen.history[gecko]?.completed)
        assertTrue(screen.runs.isEmpty())
    }

    @Test fun disconnectEndsActiveWorkButPreservesFinishedTimings() {
        model.dispatch(RenderAction.SelectMode(producer))
        model.connection(true)
        model.start(producer, "a", 100)
        model.connection(false, "断开")
        assertFalse(screen.runs.getValue(producer).running)
        model.connection(true)
        model.start(producer, "b", 110)
        model.producerEvent("b", ProducerEvent.Done(0, 1, false, -1, 55))
        model.connection(false, "断开")
        assertEquals(55L, screen.completedTiming(producer)?.completed)
    }
}

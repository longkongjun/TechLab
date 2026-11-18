package dev.techlab.android.gecko

import org.junit.Assert.*
import org.junit.Test

class IdlePreviewTest {
    @Test fun warmOrTakeCreatesOnceAndLatePreloadingDoesNotDuplicateTheActivePage() {
        var created = 0
        val slot = IdlePreview<Int>({ true }) { fail("healthy page was closed") }
        slot.warm { ++created }
        val active = slot.take(false) { ++created }
        slot.warm { ++created }
        slot.recycle(active)
        assertEquals(active, slot.take(false) { ++created })
        assertEquals(1, created)
    }

    @Test fun freshRequestClosesIdlePageAndUnhealthyReturnedPageIsReleased() {
        val closed = mutableListOf<Int>()
        val slot = IdlePreview<Int>({ it > 0 }, closed::add)
        slot.warm { 1 }
        assertEquals(2, slot.take(true) { 2 })
        slot.recycle(-1)
        slot.recycle(2)
        slot.recycle(3)
        assertEquals(listOf(1, -1, 3), closed)
        assertEquals(2, slot.take(false) { 4 })
    }
}

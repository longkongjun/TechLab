package dev.techlab.android.handwriting

import org.junit.Assert.*
import org.junit.Test

class BoardStateTest {
    @Test fun groupedEraseRestoresOrderAndANewEditDropsRedo() {
        val original = History(listOf("pen", "highlight", "pen2"))
        val erased = original.edit(listOf("highlight"))
        val restored = erased.undo()
        assertEquals(original.visible, restored.visible)
        assertEquals(erased.visible, restored.redo().visible)
        val newEdit = restored.edit(restored.visible + "pen3")
        assertEquals(newEdit, newEdit.redo())
        assertSame(newEdit, newEdit.edit(newEdit.visible))
    }

    @Test fun pageAndToolIntentsLeaveOtherBrushSettingsAlone() {
        val initial = BoardState(ready = true)
        val edited = initial.reduce(BoardIntent.Select(Tool.HIGHLIGHTER))
            .reduce(BoardIntent.Style(initial.highlighter.copy(size = 40f)))
            .reduce(BoardIntent.Open(BoardPage.QUESTION))
            .reduce(BoardIntent.Finger(true))
        assertEquals(BoardPage.QUESTION, edited.page)
        assertEquals(40f, edited.style.size, 0f)
        assertTrue(edited.fingerWrites)
        assertEquals(initial.pen, edited.reduce(BoardIntent.Select(Tool.PEN)).style)
    }
}

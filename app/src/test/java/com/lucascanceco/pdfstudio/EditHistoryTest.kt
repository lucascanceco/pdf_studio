package com.lucascanceco.pdfstudio

import org.junit.Assert.*
import org.junit.Test

class EditHistoryTest {
    @Test fun addUndoRedoAndBranch() {
        val initial = EditHistory<String>()
        val two = initial.add("texto").add("imagen")
        assertEquals(listOf("texto", "imagen"), two.current)
        val undone = two.undo()
        assertEquals(listOf("texto"), undone.current)
        assertEquals(two.current, undone.redo().current)
        assertFalse(undone.add("firma").canRedo)
    }

    @Test fun removeAndReplaceCanBeUndone() {
        val initial = EditHistory<String>().add("primero").add("segundo")
        assertEquals(initial.current, initial.replace(0, "modificado").undo().current)
        assertEquals(initial.current, initial.remove(0).undo().current)
    }
}

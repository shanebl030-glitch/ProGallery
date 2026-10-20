package com.shaneblackburn.progallery.core

import com.shaneblackburn.progallery.core.edit.Adjustments
import com.shaneblackburn.progallery.core.edit.EditHistory
import com.shaneblackburn.progallery.core.edit.EditState
import com.shaneblackburn.progallery.core.edit.FilterLook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditHistoryTest {
    private fun s(exposure: Float) = EditState(adjust = Adjustments(exposure = exposure))

    @Test fun pushUndoRedo() {
        var h = EditHistory()
        assertFalse(h.canUndo)
        h = h.push(s(0.1f)).push(s(0.2f))
        assertEquals(s(0.2f), h.current)
        h = h.undo()
        assertEquals(s(0.1f), h.current)
        assertTrue(h.canRedo)
        h = h.redo()
        assertEquals(s(0.2f), h.current)
        h = h.undo().push(EditState(filter = FilterLook.MONO))
        assertFalse(h.canRedo, "a new edit clears redo")
        assertEquals(h, h.push(h.current), "pushing the same state is a no-op")
    }

    @Test fun sliderDragIsOneUndoStep() {
        var h = EditHistory().push(s(0.1f))
        h = h.checkpoint()
        for (v in 1..20) h = h.replaceCurrent(s(0.1f + v / 100f))
        assertEquals(s(0.3f), h.current)
        h = h.undo()
        assertEquals(s(0.1f), h.current)
    }

    @Test fun untouchedCheckpointsAreSkipped() {
        var h = EditHistory().push(s(0.1f)).push(s(0.2f))
        h = h.checkpoint().checkpoint() // touched a slider twice without moving it
        h = h.undo()
        assertEquals(s(0.1f), h.current)
        assertEquals(EditState(), h.undo().current)
        assertEquals(h.undo().undo(), h.undo().undo().undo(), "undo past the start is a no-op")
    }

    @Test fun historyIsBounded() {
        var h = EditHistory(limit = 5)
        for (i in 1..20) h = h.push(s(i / 100f))
        assertEquals(5, h.past.size)
    }
}

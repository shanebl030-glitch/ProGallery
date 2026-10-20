package com.shaneblackburn.progallery.core.edit

/**
 * Immutable undo/redo history of [EditState]s.
 *
 * - [push] adds a discrete step (button tap).
 * - [checkpoint] + repeated [replaceCurrent] turn a continuous gesture (slider drag)
 *   into a single undo step.
 */
data class EditHistory(
    val past: List<EditState> = emptyList(),
    val current: EditState = EditState(),
    val future: List<EditState> = emptyList(),
    val limit: Int = 100,
) {
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun push(next: EditState): EditHistory {
        if (next == current) return this
        return copy(past = (past + current).takeLast(limit), current = next, future = emptyList())
    }

    /** Records the current state as an undo point without changing it. */
    fun checkpoint(): EditHistory =
        if (past.lastOrNull() == current) this
        else copy(past = (past + current).takeLast(limit), future = emptyList())

    fun replaceCurrent(next: EditState): EditHistory = if (next == current) this else copy(current = next, future = emptyList())

    fun undo(): EditHistory {
        if (past.isEmpty()) return this
        // Skip no-op checkpoints (e.g. a slider touched but not moved).
        var p = past
        var target = p.last()
        p = p.dropLast(1)
        while (target == current && p.isNotEmpty()) { target = p.last(); p = p.dropLast(1) }
        if (target == current) return copy(past = p)
        return copy(past = p, current = target, future = listOf(current) + future)
    }

    fun redo(): EditHistory {
        if (future.isEmpty()) return this
        return copy(past = (past + current).takeLast(limit), current = future.first(), future = future.drop(1))
    }
}

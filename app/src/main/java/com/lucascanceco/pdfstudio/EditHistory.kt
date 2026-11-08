package com.lucascanceco.pdfstudio

/** Immutable snapshots. A gesture is committed once, not once per pointer event. */
data class EditHistory<T>(
    val current: List<T> = emptyList(),
    val past: List<List<T>> = emptyList(),
    val future: List<List<T>> = emptyList()
) {
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun commit(next: List<T>): EditHistory<T> = if (next == current) this
        else copy(current = next.toList(), past = (past + listOf(current)).takeLast(100), future = emptyList())

    fun add(item: T) = commit(current + item)
    fun remove(index: Int) = commit(current.filterIndexed { i, _ -> i != index })
    fun replace(index: Int, item: T) = commit(current.toMutableList().also { it[index] = item })

    fun undo(): EditHistory<T> = if (!canUndo) this
        else copy(current = past.last(), past = past.dropLast(1), future = future + listOf(current))

    fun redo(): EditHistory<T> = if (!canRedo) this
        else copy(current = future.last(), past = past + listOf(current), future = future.dropLast(1))
}

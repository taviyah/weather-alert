package com.weatheralert.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Long-press-and-drag reordering for a [LazyListState] whose reorderable rows are keyed by a
 * stable String id.
 *
 * Hand-rolled rather than pulled in as a dependency, mostly so the drag can be told to hold still
 * on e-paper: a library that animates displaced neighbours would repaint the panel on every
 * pointer event.
 *
 * Reordering happens against a local copy while the finger is down and is written to storage once
 * on release, so a drag across four places is one disk write rather than four.
 */
class ReorderState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    /** Keys this list is allowed to move. Banners and the footer are not draggable. */
    private val draggableKeys: () -> Set<String>,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onCommit: () -> Unit,
) {
    var draggingKey by mutableStateOf<String?>(null)
        private set

    /** Pixels the dragged row has travelled from where it started. */
    var dragOffset by mutableStateOf(0f)
        private set

    fun onDragStart(key: String) {
        if (key !in draggableKeys()) return
        draggingKey = key
        dragOffset = 0f
    }

    fun onDrag(delta: Offset) {
        val key = draggingKey ?: return
        dragOffset += delta.y

        val visible = listState.layoutInfo.visibleItemsInfo
        val current = visible.firstOrNull { it.key == key } ?: return
        // Where the dragged row's middle currently sits: its laid-out position plus the
        // translation being painted on it.
        val draggedCentre = current.offset + current.size / 2f + dragOffset

        val target = visible.firstOrNull { candidate ->
            candidate.key != key &&
                (candidate.key as? String) in draggableKeys() &&
                draggedCentre.toInt() in candidate.offset..(candidate.offset + candidate.size)
        } ?: return

        onMove(current.index, target.index)
        // The row is about to be laid out in the target's slot, so the translation has to shrink
        // by the distance between slots or the row would visibly jump by its own height.
        dragOffset -= (target.offset - current.offset)
    }

    fun onDragEnd() {
        if (draggingKey != null) scope.launch { onCommit() }
        reset()
    }

    fun reset() {
        draggingKey = null
        dragOffset = 0f
    }
}

@Composable
fun rememberReorderState(
    listState: LazyListState,
    scope: CoroutineScope,
    draggableKeys: () -> Set<String>,
    onMove: (from: Int, to: Int) -> Unit,
    onCommit: () -> Unit,
): ReorderState {
    // The state object is remembered across recompositions, so it must not capture this
    // composition's lambdas: on first composition the card list is still empty, which froze
    // draggableKeys into a permanently empty set and made every row undraggable.
    val keys by rememberUpdatedState(draggableKeys)
    val move by rememberUpdatedState(onMove)
    val commit by rememberUpdatedState(onCommit)
    return remember(listState) {
        ReorderState(
            listState = listState,
            scope = scope,
            draggableKeys = { keys() },
            onMove = { from, to -> move(from, to) },
            onCommit = { commit() },
        )
    }
}

/**
 * Attach to each reorderable row, not to the LazyColumn.
 *
 * On the LazyColumn's own modifier this sits *outside* the list's internal scroll modifier, and
 * Compose dispatches the Main pass child-first — so the scrollable consumed every move and
 * cancelled the drag the instant the finger travelled. On the row it sits inside the scrollable
 * and wins. Once a pointer is captured the row keeps receiving events after the finger leaves its
 * bounds, so the drag still works across the whole list.
 */
fun Modifier.reorderableItem(state: ReorderState, key: String): Modifier =
    pointerInput(state, key) {
        detectDragGesturesAfterLongPress(
            onDragStart = { state.onDragStart(key) },
            onDrag = { change, amount ->
                change.consume()
                state.onDrag(amount)
            },
            onDragEnd = { state.onDragEnd() },
            onDragCancel = { state.reset() },
        )
    }

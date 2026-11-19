package com.weatheralert.work

import kotlinx.coroutines.delay

/**
 * Runs [attempt] on every item, then again on only the items that failed, pausing [pausesMs]
 * between rounds. Returns whatever still failed after the last round.
 *
 * Retrying per item matters because failures are usually partial. On 2026-10-02 a check on the
 * HiBreak Pro timed out for 2 of 3 places while the third succeeded; the old all-or-nothing rule
 * only retried when *every* place failed, so those two sat six hours stale until the next run.
 * The pauses give a radio that is just waking up, or a briefly congested server, time to recover.
 */
suspend fun <T> retryFailed(
    items: List<T>,
    pausesMs: List<Long>,
    attempt: suspend (T) -> Boolean,
): List<T> {
    var pending = items.filterNot { attempt(it) }
    for (pause in pausesMs) {
        if (pending.isEmpty()) break
        delay(pause)
        pending = pending.filterNot { attempt(it) }
    }
    return pending
}

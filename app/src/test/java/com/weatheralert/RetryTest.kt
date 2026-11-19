package com.weatheralert

import com.weatheralert.work.retryFailed
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryTest {

    @Test
    fun `everything succeeding needs one round and no waiting`() = runTest {
        val calls = mutableListOf<String>()
        val failed = retryFailed(listOf("a", "b", "c"), listOf(20_000L, 60_000L)) { calls += it; true }
        assertTrue(failed.isEmpty())
        assertEquals(listOf("a", "b", "c"), calls)
        assertEquals(0L, currentTime)
    }

    /** The 2026-10-02 case: two of three time out once, then recover. */
    @Test
    fun `only the failed items are retried`() = runTest {
        val calls = mutableListOf<String>()
        val flaky = mutableSetOf("b", "c")
        val failed = retryFailed(listOf("a", "b", "c"), listOf(20_000L, 60_000L)) {
            calls += it
            !flaky.remove(it)
        }
        assertTrue(failed.isEmpty())
        assertEquals(listOf("a", "b", "c", "b", "c"), calls)
        assertEquals(20_000L, currentTime)
    }

    @Test
    fun `a persistent failure is returned after every pause is used`() = runTest {
        var tries = 0
        val failed = retryFailed(listOf("a", "down"), listOf(20_000L, 60_000L)) {
            if (it == "down") tries++
            it != "down"
        }
        assertEquals(listOf("down"), failed)
        assertEquals(3, tries)
        assertEquals(80_000L, currentTime)
    }
}

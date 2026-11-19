package com.weatheralert

import com.weatheralert.data.BackgroundHealth
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class BackgroundHealthTest {

    private val now = 1_800_000_000_000L

    private fun hoursAgo(h: Long) = now - TimeUnit.HOURS.toMillis(h)

    @Test
    fun `a check that just ran is not stalled`() {
        assertFalse(BackgroundHealth.isStalled(hoursAgo(1), checkEveryHours = 3, nowMs = now))
    }

    /**
     * WorkManager's period is a floor and Doze batches jobs, so a 3-hour schedule running at 5
     * hours is normal. Warning at the first missed cycle would cry wolf constantly.
     */
    @Test
    fun `normal Doze batching does not count as stalled`() {
        assertFalse(BackgroundHealth.isStalled(hoursAgo(5), checkEveryHours = 3, nowMs = now))
        assertFalse(BackgroundHealth.isStalled(hoursAgo(7), checkEveryHours = 3, nowMs = now))
    }

    @Test
    fun `two and a half missed cycles is stalled`() {
        assertTrue(BackgroundHealth.isStalled(hoursAgo(8), checkEveryHours = 3, nowMs = now))
        assertTrue(BackgroundHealth.isStalled(hoursAgo(24), checkEveryHours = 3, nowMs = now))
    }

    @Test
    fun `the threshold scales with the configured interval`() {
        // On a 12-hour schedule, 20 hours is still within normal batching.
        assertFalse(BackgroundHealth.isStalled(hoursAgo(20), checkEveryHours = 12, nowMs = now))
        assertTrue(BackgroundHealth.isStalled(hoursAgo(31), checkEveryHours = 12, nowMs = now))
    }

    @Test
    fun `a fresh install has never run and is not reported as broken`() {
        assertFalse(BackgroundHealth.isStalled(0L, checkEveryHours = 3, nowMs = now))
    }

    @Test
    fun `hoursSince reports whole hours`() {
        org.junit.Assert.assertEquals(9L, BackgroundHealth.hoursSince(hoursAgo(9), now))
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Alfredo Medrano Sanchez <alfredomedranosanchez@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [AdaptiveBitrate], the pure-JVM controller fed by desktop
 * `kdeconnect.camera.stats` congestion reports. One onStats() call == one tick.
 */
class AdaptiveBitrateTest {

    private val high = AdaptiveBitrate.HIGH_THRESHOLD_BYTES // 393216 = 3/4 of 512 KB
    private val low = AdaptiveBitrate.LOW_THRESHOLD_BYTES   // 65536

    // ── Dead zone: hold ────────────────────────────────────────────────

    @Test
    fun `dead zone keeps bitrate and returns null`() {
        val c = AdaptiveBitrate(initial = 4_000_000, max = 4_000_000)
        // Any backlog strictly between the thresholds, not paused → no change.
        repeat(10) { i ->
            val backlog = low + 1 + i * 10_000L
            assertNull("tick $i in dead zone must not change bitrate", c.onStats(backlog, paused = false))
        }
        assertEquals(4_000_000, c.currentBitrate)
    }

    // ── Congestion: −30% ───────────────────────────────────────────────

    @Test
    fun `paused cuts bitrate by 30 percent`() {
        val c = AdaptiveBitrate(initial = 4_000_000, max = 4_000_000)
        assertEquals(2_800_000, c.onStats(backlogBytes = 0, paused = true))
        assertEquals(2_800_000, c.currentBitrate)
    }

    @Test
    fun `backlog at high threshold cuts bitrate by 30 percent`() {
        val c = AdaptiveBitrate(initial = 4_000_000, max = 4_000_000)
        assertEquals(2_800_000, c.onStats(backlogBytes = high, paused = false))
    }

    // ── Decrease cooldown ──────────────────────────────────────────────

    @Test
    fun `two consecutive congested ticks produce only one decrease`() {
        val c = AdaptiveBitrate(initial = 4_000_000, max = 4_000_000)
        assertEquals(2_800_000, c.onStats(backlogBytes = high, paused = false)) // tick 1
        assertNull(c.onStats(backlogBytes = high, paused = false))              // tick 2: cooldown
        // tick 3: cooldown over (3 - 1 >= 2), second cut applies
        assertEquals(1_960_000, c.onStats(backlogBytes = high, paused = false))
        assertEquals(1_960_000, c.currentBitrate)
    }

    @Test
    fun `dead-zone ticks do not reset the decrease cooldown`() {
        val c = AdaptiveBitrate(initial = 4_000_000, max = 4_000_000)
        assertEquals(2_800_000, c.onStats(backlogBytes = high, paused = false)) // tick 1: cut
        assertNull(c.onStats(backlogBytes = 200_000, paused = false))           // tick 2: dead zone
        // tick 3: exactly DOWN_COOLDOWN_TICKS after the cut → allowed again.
        assertEquals(1_960_000, c.onStats(backlogBytes = high, paused = false))
    }

    // ── Recovery: +10%, at most once every 5 ticks ─────────────────────

    @Test
    fun `empty backlog raises bitrate by 10 percent`() {
        val c = AdaptiveBitrate(initial = 1_000_000, max = 4_000_000)
        assertEquals(1_100_000, c.onStats(backlogBytes = 0, paused = false))
        assertEquals(1_100_000, c.currentBitrate)
    }

    @Test
    fun `increase happens at most once every 5 ticks`() {
        val c = AdaptiveBitrate(initial = 1_000_000, max = 4_000_000)
        var increases = 0
        val changes = mutableListOf<Int>()
        repeat(11) {
            val v = c.onStats(backlogBytes = 0, paused = false)
            if (v != null) {
                increases++
                changes.add(it + 1) // 1-based tick number
            }
        }
        // Ticks 1, 6, 11 → three increases in eleven ticks.
        assertEquals(listOf(1, 6, 11), changes)
        assertEquals(3, increases)
        assertEquals(1_331_000, c.currentBitrate) // 1_000_000 * 1.1^3
    }

    @Test
    fun `increase is capped at max`() {
        val c = AdaptiveBitrate(initial = 4_000_000, max = 4_000_000)
        // 11/10 of 4_000_000 would exceed max → clamped to current → null.
        assertNull(c.onStats(backlogBytes = 0, paused = false))
        assertEquals(4_000_000, c.currentBitrate)
    }

    @Test
    fun `increase stops exactly at max after a decrease`() {
        val c = AdaptiveBitrate(initial = 1_000_000, max = 1_100_000)
        assertEquals(1_100_000, c.onStats(backlogBytes = 0, paused = false)) // 1_000_000 → max
        // Next allowed increase tick (tick 6) stays at max → null.
        repeat(4) { assertNull(c.onStats(backlogBytes = 0, paused = false)) }
        assertNull(c.onStats(backlogBytes = 0, paused = false))
        assertEquals(1_100_000, c.currentBitrate)
    }

    // ── Floor: min ─────────────────────────────────────────────────────

    @Test
    fun `decrease is floored at min`() {
        val c = AdaptiveBitrate(initial = 600_000, max = 600_000, min = 500_000)
        // 600_000 * 7/10 = 420_000 → clamped up to the floor.
        assertEquals(500_000, c.onStats(backlogBytes = high, paused = false))
        // Further congested ticks cannot go below min → null.
        repeat(6) { assertNull(c.onStats(backlogBytes = high + 100_000, paused = true)) }
        assertEquals(500_000, c.currentBitrate)
    }

    @Test
    fun `default min is 500 kbps`() {
        val c = AdaptiveBitrate(initial = 4_000_000, max = 4_000_000)
        // 4M → 2.8M → 1.96M → 1.372M → 960_400 → 672_280 → 500_000 (floor)
        val expected = listOf(2_800_000, 1_960_000, 1_372_000, 960_400, 672_280, 500_000)
        val got = mutableListOf<Int>()
        var tick = 0
        while (got.size < expected.size && tick < 60) {
            tick++
            c.onStats(backlogBytes = high, paused = false)?.let { got.add(it) }
        }
        assertEquals(expected, got)
    }

    // ── No change → null ───────────────────────────────────────────────

    @Test
    fun `returns null when the rule fires but the bitrate does not change`() {
        // currentBitrate already at min, congested → target == current → null.
        val c = AdaptiveBitrate(initial = 500_000, max = 4_000_000, min = 500_000)
        assertNull(c.onStats(backlogBytes = 1_000_000, paused = true))
        assertEquals(500_000, c.currentBitrate)
    }

    // ── Boundary values ────────────────────────────────────────────────

    @Test
    fun `low threshold boundary is inclusive for increases`() {
        val c = AdaptiveBitrate(initial = 1_000_000, max = 4_000_000)
        // Exactly at the low threshold → recovery branch.
        assertEquals(1_100_000, c.onStats(backlogBytes = low, paused = false))
        // One byte above → dead zone.
        val d = AdaptiveBitrate(initial = 1_000_000, max = 4_000_000)
        assertNull(d.onStats(backlogBytes = low + 1, paused = false))
    }

    @Test
    fun `high threshold boundary is inclusive for decreases`() {
        val c = AdaptiveBitrate(initial = 1_000_000, max = 4_000_000)
        // Exactly at the high threshold → congestion branch.
        assertEquals(700_000, c.onStats(backlogBytes = high, paused = false))
        // One byte below → dead zone.
        val d = AdaptiveBitrate(initial = 1_000_000, max = 4_000_000)
        assertNull(d.onStats(backlogBytes = high - 1, paused = false))
    }

    @Test
    fun `paused overrides a comfortable backlog`() {
        val c = AdaptiveBitrate(initial = 1_000_000, max = 4_000_000)
        assertEquals(700_000, c.onStats(backlogBytes = 0, paused = true))
    }

    @Test
    fun `congested backlog overrides a would-be increase`() {
        val c = AdaptiveBitrate(initial = 1_000_000, max = 4_000_000)
        // Huge backlog with paused=false still hits the congestion branch.
        assertEquals(700_000, c.onStats(backlogBytes = 10_000_000, paused = false))
    }

    // ── Initial clamping ───────────────────────────────────────────────

    @Test
    fun `initial bitrate is clamped into the range`() {
        assertEquals(500_000, AdaptiveBitrate(initial = 100_000, max = 4_000_000, min = 500_000).currentBitrate)
        assertEquals(4_000_000, AdaptiveBitrate(initial = 9_000_000, max = 4_000_000).currentBitrate)
    }
}

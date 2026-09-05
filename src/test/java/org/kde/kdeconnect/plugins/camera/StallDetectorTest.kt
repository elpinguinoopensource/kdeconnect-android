/*
 * SPDX-FileCopyrightText: 2026 Alfredo Medrano Sanchez <alfredomedranosanchez@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallDetectorTest {

    private val stallNanos = 10_000_000_000L  // 10 s
    private val twoSeconds = 2_000_000_000L    // 2 s

    @Test
    fun `fresh output is not stalled`() {
        val det = StallDetector(stallNanos = stallNanos)
        // lastOutput was just set (now == lastOutput)
        assertFalse(det.tick(nowNanos = 0, lastOutputNanos = 0, buffered = 0, maxBuffered = 0, streamClosed = false))
    }

    @Test
    fun `11 seconds of silence triggers stall`() {
        val det = StallDetector(stallNanos = stallNanos)
        val lastOutput = 0L
        val now = stallNanos + 1  // 10 s + 1 ns
        assertTrue(det.tick(nowNanos = now, lastOutputNanos = lastOutput, buffered = 0, maxBuffered = 0, streamClosed = false))
    }

    @Test
    fun `closed stream does not trigger backpressure rule`() {
        val det = StallDetector(stallNanos = stallNanos, backpressureThreshold = 5)
        val now = 0L
        val lastOutput = 0L
        // 10 consecutive ticks with closed stream and full buffer — no stall
        // because the no-output rule fires first at 10 s, so we stay under that.
        // Use a fresh detector with huge stallNanos to isolate backpressure.
        val det2 = StallDetector(stallNanos = Long.MAX_VALUE, backpressureThreshold = 5)
        repeat(10) { i ->
            val tick = det2.tick(
                nowNanos = now + i * twoSeconds,
                lastOutputNanos = lastOutput,
                buffered = 2_000_000,
                maxBuffered = 2_000_000,
                streamClosed = true,  // closed!
            )
            assertFalse("tick $i should not stall when stream is closed", tick)
        }
    }

    @Test
    fun `4 consecutive full-buffer ticks not stalled, 5th is stalled`() {
        val det = StallDetector(stallNanos = Long.MAX_VALUE, backpressureThreshold = 5)
        val now = 0L
        val lastOutput = 0L

        // Ticks 1-4: not stalled
        repeat(4) { i ->
            val stalled = det.tick(
                nowNanos = now + i * twoSeconds,
                lastOutputNanos = lastOutput,
                buffered = 2_000_000,
                maxBuffered = 2_000_000,
                streamClosed = false,
            )
            assertFalse("tick ${i + 1} should not stall", stalled)
        }

        // Tick 5: stalled
        val stalled = det.tick(
            nowNanos = now + 4 * twoSeconds,
            lastOutputNanos = lastOutput,
            buffered = 2_000_000,
            maxBuffered = 2_000_000,
            streamClosed = false,
        )
        assertTrue("tick 5 should stall", stalled)
    }

    @Test
    fun `counters reset after a frame arrives`() {
        val det = StallDetector(stallNanos = Long.MAX_VALUE, backpressureThreshold = 5)
        val now = 0L
        val lastOutput = 0L

        // 4 full-buffer ticks
        repeat(4) { i ->
            det.tick(
                nowNanos = now + i * twoSeconds,
                lastOutputNanos = lastOutput,
                buffered = 2_000_000,
                maxBuffered = 2_000_000,
                streamClosed = false,
            )
        }

        // A frame arrives — reset backpressure counter
        det.resetBackpressure()

        // 4 more full-buffer ticks — should NOT stall (counter was reset)
        repeat(4) { i ->
            val stalled = det.tick(
                nowNanos = now + (4 + i) * twoSeconds,
                lastOutputNanos = lastOutput,
                buffered = 2_000_000,
                maxBuffered = 2_000_000,
                streamClosed = false,
            )
            assertFalse("tick ${5 + i} should not stall after reset", stalled)
        }
    }

    @Test
    fun `stats tick cadence - true exactly every 5th tick`() {
        val det = StallDetector(stallNanos = Long.MAX_VALUE, statsIntervalTicks = 5)
        val now = 0L
        val lastOutput = 0L

        // Ticks 1-20: shouldLogStats true at 5, 10, 15, 20
        val expectedTrue = setOf(5, 10, 15, 20)
        for (i in 1..20) {
            det.tick(
                nowNanos = now + (i - 1) * twoSeconds,
                lastOutputNanos = lastOutput,
                buffered = 0,
                maxBuffered = 0,
                streamClosed = false,
            )
            val expected = i in expectedTrue
            assertEquals(
                "shouldLogStats at tick $i",
                expected,
                det.shouldLogStats()
            )
        }
    }
}

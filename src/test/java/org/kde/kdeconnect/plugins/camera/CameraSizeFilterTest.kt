/*
 * SPDX-FileCopyrightText: 2026 Alfredo Medrano Sanchez <alfredomedranosanchez@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSizeFilterTest {

    @Test
    fun rejectsZeroAndNegativeDimensions() {
        val input = listOf(
            CameraSize(0, 480, 30),
            CameraSize(640, 0, 30),
            CameraSize(-1, 480, 30),
            CameraSize(640, -1, 30),
            CameraSize(0, 0, 30),
        )
        val result = CameraSizeFilter.sanitize(input)
        assertTrue(result.isEmpty())
    }

    @Test
    fun rejectsOversizedResolutions() {
        val input = listOf(
            CameraSize(3840, 2160, 30), // 4K — over 1080p pixel count
            CameraSize(2560, 1440, 30), // 1440p — over 1080p pixel count
            CameraSize(1920, 1080, 30), // exactly 1080p — should be kept
        )
        val result = CameraSizeFilter.sanitize(input)
        assertEquals(1, result.size)
        assertEquals(1920, result[0].width)
        assertEquals(1080, result[0].height)
    }

    @Test
    fun keepsStandardAspectRatios() {
        val input = listOf(
            CameraSize(640, 480, 30),   // 4:3
            CameraSize(1280, 720, 30),  // 16:9
            CameraSize(1920, 1080, 30), // 16:9
            CameraSize(1000, 600, 30),  // 5:3 — not standard, should be rejected
        )
        val result = CameraSizeFilter.sanitize(input)
        assertEquals(3, result.size)
        assertTrue(result.none { it.width == 1000 && it.height == 600 })
    }

    @Test
    fun dedupesSameResolutionKeepingMaxFps() {
        val input = listOf(
            CameraSize(1280, 720, 30),
            CameraSize(1280, 720, 60),
            CameraSize(1280, 720, 15),
        )
        val result = CameraSizeFilter.sanitize(input)
        assertEquals(1, result.size)
        assertEquals(60, result[0].fps)
    }

    @Test
    fun sortsByPixelsDescThenFpsDesc() {
        val input = listOf(
            CameraSize(640, 480, 30),
            CameraSize(1280, 720, 30),
            CameraSize(1280, 720, 60),
            CameraSize(1920, 1080, 30),
        )
        val result = CameraSizeFilter.sanitize(input)
        // After dedup: 1920x1080@30, 1280x720@60, 640x480@30
        assertEquals(3, result.size)
        // 1920x1080 first (most pixels)
        assertEquals(1920, result[0].width)
        assertEquals(1080, result[0].height)
        // 1280x720@60 (deduped: higher fps kept)
        assertEquals(1280, result[1].width)
        assertEquals(720, result[1].height)
        assertEquals(60, result[1].fps)
        // 640x480 last
        assertEquals(640, result[2].width)
        assertEquals(480, result[2].height)
    }

    @Test
    fun truncatesToMaxEntries() {
        val input = listOf(
            CameraSize(1920, 1080, 30),
            CameraSize(1280, 720, 60),
            CameraSize(1280, 720, 30),
            CameraSize(960, 540, 30),  // 16:9
            CameraSize(854, 480, 30),  // ~16:9
            CameraSize(640, 480, 30),  // 4:3
            CameraSize(320, 240, 30),  // 4:3
            CameraSize(176, 144, 30),  // 4:3 (11:9 ≈ not standard — hmm, 176/144 = 1.222, 4/3 = 1.333, diff = 0.111, tolerance = 0.0267 — rejected)
        )
        val result = CameraSizeFilter.sanitize(input, maxEntries = 3)
        assertEquals(3, result.size)
    }

    @Test
    fun emptyInputReturnsEmptyList() {
        val result = CameraSizeFilter.sanitize(emptyList())
        assertTrue(result.isEmpty())
    }
}

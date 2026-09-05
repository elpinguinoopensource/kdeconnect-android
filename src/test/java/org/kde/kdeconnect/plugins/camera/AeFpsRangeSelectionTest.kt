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
 * Unit tests for the pure AE target fps-range selection used to lock the
 * capture cadence (see selectAeFpsRange in CameraSession.kt).
 */
class AeFpsRangeSelectionTest {

    @Test
    fun exactFixedRangeWins() {
        val available = arrayOf(
            FpsRange(10, 30),
            FpsRange(15, 15), // exact match for target 15
            FpsRange(7, 15),
        )
        assertEquals(FpsRange(15, 15), selectAeFpsRange(available, 15))
    }

    @Test
    fun exactFixedRangePreferredEvenIfNotFirst() {
        val available = arrayOf(
            FpsRange(1, 30),
            FpsRange(12, 30),
            FpsRange(30, 30),
        )
        assertEquals(FpsRange(30, 30), selectAeFpsRange(available, 30))
    }

    @Test
    fun noEligibleRangeReturnsNull() {
        // All uppers below the target: nothing can sustain 60fps.
        val available = arrayOf(
            FpsRange(10, 30),
            FpsRange(15, 30),
            FpsRange(7, 15),
        )
        assertNull(selectAeFpsRange(available, 60))
    }

    @Test
    fun targetAboveEveryUpperReturnsNull() {
        val available = arrayOf(FpsRange(1, 24), FpsRange(24, 24))
        assertNull(selectAeFpsRange(available, 25))
    }

    @Test
    fun severalCoveringRangesPickHighestLowerThenNarrowestBand() {
        // Target 24. Candidates covering it:
        //   (7,30)  → lower gap 17, band 23
        //   (10,30) → lower gap 14, band 20
        //   (15,30) → lower gap 9,  band 15  ← best lower gap
        //   (15,60) → lower gap 9,  band 45  ← same lower gap, wider band
        // Expect (15,30): closest lower bound, then narrowest band.
        val available = arrayOf(
            FpsRange(7, 30),
            FpsRange(10, 30),
            FpsRange(15, 60),
            FpsRange(15, 30),
        )
        assertEquals(FpsRange(15, 30), selectAeFpsRange(available, 24))
    }

    @Test
    fun narrowestBandBreaksTieOnLowerGap() {
        // Same lower bound, different widths: narrowest must win.
        val available = arrayOf(
            FpsRange(20, 120),
            FpsRange(20, 30),
        )
        assertEquals(FpsRange(20, 30), selectAeFpsRange(available, 24))
    }

    @Test
    fun higherLowerBoundPreferredOverNarrowerBand() {
        // Lower-bound proximity dominates band width: (12,15) band 3 but
        // gap 12 vs (20,30) band 10 gap 4 → (20,30) wins.
        val available = arrayOf(
            FpsRange(12, 15), // upper < 24 → not eligible anyway
            FpsRange(1, 30),
            FpsRange(20, 30),
        )
        assertEquals(FpsRange(20, 30), selectAeFpsRange(available, 24))
    }

    @Test
    fun rangeExactlyAtTargetUpperIsEligible() {
        // upper >= target (inclusive boundary).
        val available = arrayOf(FpsRange(1, 24), FpsRange(1, 30))
        assertEquals(FpsRange(1, 24), selectAeFpsRange(available, 24))
    }

    @Test
    fun emptyArrayReturnsNull() {
        assertNull(selectAeFpsRange(emptyArray(), 30))
    }

    @Test
    fun nullArrayReturnsNull() {
        assertNull(selectAeFpsRange(null, 30))
    }

    @Test
    fun nonPositiveTargetReturnsNull() {
        val available = arrayOf(FpsRange(1, 30))
        assertNull(selectAeFpsRange(available, 0))
        assertNull(selectAeFpsRange(available, -5))
    }
}

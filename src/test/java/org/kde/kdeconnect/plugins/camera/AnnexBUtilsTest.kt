/*
 * SPDX-FileCopyrightText: 2026 Alfredo Medrano Sanchez <alfredomedranosanchez@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class AnnexBUtilsTest {

    @Test
    fun isByteBufferModeTrueForAnnexB() {
        // Starts with 00 00 00 01 followed by SPS NAL header (0x67)
        val data = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1E)
        val buf = ByteBuffer.wrap(data)
        assertTrue(AnnexBUtils.isByteBufferMode(buf))
        // Verify non-destructive: position restored
        assertEquals(0, buf.position())
    }

    @Test
    fun isByteBufferModeFalseForRaw() {
        // Raw SPS NAL without start code
        val data = byteArrayOf(0x67, 0x42, 0, 0x1E)
        val buf = ByteBuffer.wrap(data)
        assertFalse(AnnexBUtils.isByteBufferMode(buf))
    }

    @Test
    fun isByteBufferModeFalseForShortBuffer() {
        val data = byteArrayOf(0, 0, 1)
        val buf = ByteBuffer.wrap(data)
        assertFalse(AnnexBUtils.isByteBufferMode(buf))
    }

    @Test
    fun toAnnexBPrependsCsdBeforeIdr() {
        // SPS (NAL type 7): header byte 0x67 & 0x1F == 7
        val sps = byteArrayOf(0x67, 0x42, 0, 0x1E)
        // PPS (NAL type 8): header byte 0x68 & 0x1F == 8
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38, 0x80.toByte())
        // IDR (NAL type 5): header byte 0x65 & 0x1F == 5
        val idr = byteArrayOf(0x65, 0x88.toByte(), 0x80.toByte(), 0x40)

        val csd = listOf(
            ByteBuffer.wrap(sps),
            ByteBuffer.wrap(pps),
        )
        val idrBuf = ByteBuffer.wrap(idr)

        val result = AnnexBUtils.toAnnexB(idrBuf, csd, includeCsd = true)

        // Expected: start_code + SPS + start_code + PPS + start_code + IDR
        val expected = byteArrayOf(
            0, 0, 0, 1,                         // start code
            0x67, 0x42, 0, 0x1E,                // SPS
            0, 0, 0, 1,                         // start code
            0x68, 0xCE.toByte(), 0x38, 0x80.toByte(), // PPS
            0, 0, 0, 1,                         // start code
            0x65, 0x88.toByte(), 0x80.toByte(), 0x40, // IDR
        )
        assertArrayEquals(expected, result)

        // Verify NAL types in order: 7, 8, 5
        assertEquals(7, AnnexBUtils.nalType(result, 4))   // first NAL after first start code
        assertEquals(8, AnnexBUtils.nalType(result, 12))  // second NAL after second start code
        assertEquals(5, AnnexBUtils.nalType(result, 20))  // third NAL after third start code
    }

    @Test
    fun toAnnexBWithIncludeCsdFalseEmitsOneStartCodePlusPayload() {
        val sps = byteArrayOf(0x67, 0x42, 0, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38, 0x80.toByte())
        val idr = byteArrayOf(0x65, 0x88.toByte(), 0x80.toByte(), 0x40)

        val csd = listOf(
            ByteBuffer.wrap(sps),
            ByteBuffer.wrap(pps),
        )
        val idrBuf = ByteBuffer.wrap(idr)

        val result = AnnexBUtils.toAnnexB(idrBuf, csd, includeCsd = false)

        // Expected: just start_code + IDR payload (no CSD)
        val expected = byteArrayOf(
            0, 0, 0, 1,                         // start code
            0x65, 0x88.toByte(), 0x80.toByte(), 0x40, // IDR
        )
        assertArrayEquals(expected, result)
    }

    @Test
    fun toAnnexBHonorsPositionAndLimit() {
        val sps = byteArrayOf(0x67, 0x42, 0, 0x1E)
        val idr = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x65, 0x88.toByte(), 0xFF.toByte(), 0xFF.toByte())

        // Buffer with position=2, limit=4 => only bytes at index 2 and 3
        val idrBuf = ByteBuffer.wrap(idr)
        idrBuf.position(2)
        idrBuf.limit(4)

        val csd = listOf(ByteBuffer.wrap(sps))
        val result = AnnexBUtils.toAnnexB(idrBuf, csd, includeCsd = true)

        // Expected: start_code + SPS + start_code + [0x65, 0x88]
        val expected = byteArrayOf(
            0, 0, 0, 1,                         // start code
            0x67, 0x42, 0, 0x1E,                // SPS
            0, 0, 0, 1,                         // start code
            0x65, 0x88.toByte(),                 // IDR (only 2 bytes from position to limit)
        )
        assertArrayEquals(expected, result)

        // Verify buffer position is restored
        assertEquals(2, idrBuf.position())
    }

    @Test
    fun nalTypeExtractsCorrectType() {
        assertEquals(7, AnnexBUtils.nalType(byteArrayOf(0x67), 0))  // SPS
        assertEquals(8, AnnexBUtils.nalType(byteArrayOf(0x68), 0))  // PPS
        assertEquals(5, AnnexBUtils.nalType(byteArrayOf(0x65), 0))  // IDR
        assertEquals(1, AnnexBUtils.nalType(byteArrayOf(0x41), 0))  // non-IDR slice
    }
}

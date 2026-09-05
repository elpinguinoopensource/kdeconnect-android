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

    // ---- csdPrependSize tests: helper must match toAnnexB's real output ----

    /**
     * toAnnexB output size == payload start code + csdPrependSize + payload.
     * Asserts the helper and the actual serialized result agree exactly.
     */
    private fun assertSizeMatchesToAnnexB(
        label: String,
        payloadBuf: ByteBuffer,
        csd: List<ByteBuffer>?,
        includeCsd: Boolean,
    ) {
        // The helper works on a copied payload (toAnnexB semantics).
        val copy = ByteBuffer.allocate(payloadBuf.remaining())
        val saved = payloadBuf.position()
        copy.put(payloadBuf)
        copy.flip()
        payloadBuf.position(saved)
        val payloadBytes = ByteArray(copy.remaining())
        copy.get(payloadBytes)

        val csdBytes = AnnexBUtils.csdPrependSize(csd, includeCsd, payloadBytes)
        val result = AnnexBUtils.toAnnexB(payloadBuf, csd, includeCsd)
        val expected = AnnexBUtils.START_CODE.size + payloadBytes.size
        assertEquals(
            "$label: toAnnexB size must equal start code + payload + csdPrependSize",
            result.size,
            expected + csdBytes,
        )
    }

    @Test
    fun csdPrependSizeMatchesToAnnexBForRawCsd() {
        val sps = byteArrayOf(0x67, 0x42, 0, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38, 0x80.toByte())
        val idr = byteArrayOf(0x65, 0x88.toByte(), 0x80.toByte(), 0x40)
        val csd = listOf(ByteBuffer.wrap(sps), ByteBuffer.wrap(pps))

        // Raw CSD: 2 start codes + 4 + 4 blob bytes = 16.
        assertEquals(16, AnnexBUtils.csdPrependSize(csd, includeCsd = true, payload = idr))
        assertSizeMatchesToAnnexB("raw csd", ByteBuffer.wrap(idr), csd, includeCsd = true)
    }

    @Test
    fun csdPrependSizeMatchesToAnnexBForAnnexBCsd() {
        // Vendor encoders may emit CSD that already carries start codes:
        // no extra start code is counted, only the blob bytes (7 + 7).
        val annexbSps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x1E)
        val annexbPps = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x38)
        val idr = byteArrayOf(0x65, 0x88.toByte(), 0x80.toByte(), 0x40)
        val csd = listOf(ByteBuffer.wrap(annexbSps), ByteBuffer.wrap(annexbPps))

        assertEquals(14, AnnexBUtils.csdPrependSize(csd, includeCsd = true, payload = idr))
        assertSizeMatchesToAnnexB("annexb csd", ByteBuffer.wrap(idr), csd, includeCsd = true)
    }

    @Test
    fun csdPrependSizeZeroForNullEmptyOrExcludedCsd() {
        val sps = byteArrayOf(0x67, 0x42, 0, 0x1E)
        val idr = byteArrayOf(0x65, 0x88.toByte(), 0x80.toByte(), 0x40)

        // csd == null
        assertEquals(0, AnnexBUtils.csdPrependSize(null, includeCsd = true, payload = idr))
        assertSizeMatchesToAnnexB("null csd", ByteBuffer.wrap(idr), null, includeCsd = true)

        // csd empty
        assertEquals(0, AnnexBUtils.csdPrependSize(emptyList(), includeCsd = true, payload = idr))
        assertSizeMatchesToAnnexB("empty csd", ByteBuffer.wrap(idr), emptyList(), includeCsd = true)

        // includeCsd == false
        val csd = listOf(ByteBuffer.wrap(sps))
        assertEquals(0, AnnexBUtils.csdPrependSize(csd, includeCsd = false, payload = idr))
        assertSizeMatchesToAnnexB("includeCsd=false", ByteBuffer.wrap(idr), csd, includeCsd = false)
    }

    @Test
    fun csdPrependSizeZeroWhenPayloadAlreadyContainsSps() {
        // Encoder bundled SPS at the head of the access unit (start code +
        // type 7): toAnnexB skips CSD, so the helper must report 0.
        val sps = byteArrayOf(0x67, 0x42, 0, 0x1E)
        val idr = byteArrayOf(0x65, 0x88.toByte(), 0x80.toByte(), 0x40)
        val csd = listOf(ByteBuffer.wrap(sps))

        val payloadWithSps = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + idr
        assertEquals(
            0,
            AnnexBUtils.csdPrependSize(csd, includeCsd = true, payload = payloadWithSps),
        )
        assertSizeMatchesToAnnexB(
            "sps already embedded",
            ByteBuffer.wrap(payloadWithSps),
            csd,
            includeCsd = true,
        )

        // Raw SPS head (no start code) is detected too.
        val rawSpsHead = sps + idr
        assertEquals(0, AnnexBUtils.csdPrependSize(csd, includeCsd = true, payload = rawSpsHead))
    }

    @Test
    fun csdPrependSizeByteBufferOverloadMatchesPayloadOverload() {
        // The ByteBuffer peek overload (used by CameraSession's fast path) must
        // agree with the ByteArray overload for the same logical content.
        val sps = byteArrayOf(0x67, 0x42, 0, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38, 0x80.toByte())
        val idr = byteArrayOf(0x65, 0x88.toByte(), 0x80.toByte(), 0x40)
        val csd = listOf(ByteBuffer.wrap(sps), ByteBuffer.wrap(pps))

        val withSc = byteArrayOf(0, 0, 0, 1) + idr
        assertEquals(
            AnnexBUtils.csdPrependSize(csd, includeCsd = true, payload = withSc),
            AnnexBUtils.csdPrependSize(csd, includeCsd = true, buffer = ByteBuffer.wrap(withSc)),
        )

        val spsEmbedded = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + idr
        assertEquals(
            AnnexBUtils.csdPrependSize(csd, includeCsd = true, payload = spsEmbedded),
            AnnexBUtils.csdPrependSize(csd, includeCsd = true, buffer = ByteBuffer.wrap(spsEmbedded)),
        )

        // Non-destructive: position/limit untouched by the peek.
        val buf = ByteBuffer.wrap(withSc)
        buf.position(1)
        buf.limit(5)
        AnnexBUtils.csdPrependSize(csd, includeCsd = true, buffer = buf)
        assertEquals(1, buf.position())
        assertEquals(5, buf.limit())
    }

    @Test
    fun firstNalIsSpsDetectsTypes() {
        assertTrue(AnnexBUtils.firstNalIsSps(ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x67, 0))))
        assertTrue(AnnexBUtils.firstNalIsSps(ByteBuffer.wrap(byteArrayOf(0x67, 0x42, 0, 0x1E, 0))))
        assertFalse(AnnexBUtils.firstNalIsSps(ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x65, 0)))) // IDR
        assertFalse(AnnexBUtils.firstNalIsSps(ByteBuffer.wrap(byteArrayOf(0x41, 0x1, 0x2, 0x3, 0x4)))) // slice
        assertFalse(AnnexBUtils.firstNalIsSps(ByteBuffer.wrap(byteArrayOf(0, 0, 1, 0x67)))) // < 5 bytes
    }

    /**
     * Mirror of CameraSession's fast-path prefix builder: start code + CSD
     * blobs (+ trailing payload start code) into a scratch array. Kept here so
     * the byte layout the session produces can be compared against the
     * reference toAnnexB output without needing the Android framework.
     */
    private fun buildSessionPrefix(csd: List<ByteBuffer>, csdLen: Int): Pair<ByteArray, Int> {
        val s = ByteArray(AnnexBUtils.START_CODE.size + csdLen)
        var pos = 0
        for (csdBuf in csd) {
            if (AnnexBUtils.isByteBufferMode(csdBuf)) {
                val saved = csdBuf.position()
                val n = csdBuf.remaining()
                csdBuf.get(s, pos, n)
                csdBuf.position(saved)
                pos += n
            } else {
                System.arraycopy(AnnexBUtils.START_CODE, 0, s, pos, AnnexBUtils.START_CODE.size)
                pos += AnnexBUtils.START_CODE.size
                val saved = csdBuf.position()
                val n = csdBuf.remaining()
                csdBuf.get(s, pos, n)
                csdBuf.position(saved)
                pos += n
            }
        }
        System.arraycopy(AnnexBUtils.START_CODE, 0, s, pos, AnnexBUtils.START_CODE.size)
        pos += AnnexBUtils.START_CODE.size
        return s to pos
    }

    @Test
    fun sessionFastPathLayoutMatchesToAnnexB() {
        val sps = byteArrayOf(0x67, 0x42, 0, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38, 0x80.toByte())
        val idr = ByteArray(300) { ((it * 13 + 5) and 0xFF).toByte() }
        idr[0] = 0x65 // IDR NAL header

        val cases = mapOf(
            "raw csd" to listOf(ByteBuffer.wrap(sps), ByteBuffer.wrap(pps)),
            "annex-b csd" to listOf(
                ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + sps),
                ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + pps),
            ),
        )

        for ((label, csd) in cases) {
            val payloadBuf = ByteBuffer.wrap(idr)

            // Reference: whole access unit in one shot.
            val reference = AnnexBUtils.toAnnexB(payloadBuf, csd, includeCsd = true)

            // Fast path: scratch prefix + direct payload buffer through the stream.
            val csdLen = AnnexBUtils.csdPrependSize(csd, includeCsd = true, buffer = payloadBuf)
            assertTrue("$label: expected non-zero csdLen", csdLen > 0)
            val (scratch, prefixLen) = buildSessionPrefix(csd, csdLen)

            val stream = StreamedPayloadInputStream(maxBufferedBytes = 1024 * 1024)
            stream.write(payloadBuf, prefix = scratch, prefixLen = prefixLen)
            stream.finish()

            val out = ByteArray(reference.size + 64)
            var off = 0
            while (true) {
                val n = stream.read(out, off, out.size - off)
                if (n == -1) break
                off += n
            }
            assertArrayEquals(
                "$label: fast-path bytes must equal toAnnexB output",
                reference,
                out.copyOf(off),
            )
            assertEquals("$label: payload buffer untouched", 0, payloadBuf.position())
            stream.close()
        }
    }

    @Test
    fun sessionFastPathSkipsCsdWhenPayloadBundlesSps() {
        // Keyframe that already carries SPS: csdPrependSize reports 0, so the
        // session takes the no-CSD branch (payload start code only) and must
        // still match toAnnexB byte for byte.
        val sps = byteArrayOf(0x67, 0x42, 0, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38.toByte(), 0x80.toByte())
        val idr = byteArrayOf(0x65, 0x11, 0x22, 0x33)
        val bundled = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps +
            byteArrayOf(0, 0, 0, 1) + idr
        val csd = listOf(ByteBuffer.wrap(sps), ByteBuffer.wrap(pps))

        val payloadBuf = ByteBuffer.wrap(bundled)
        assertEquals(0, AnnexBUtils.csdPrependSize(csd, includeCsd = true, buffer = payloadBuf))

        val reference = AnnexBUtils.toAnnexB(payloadBuf, csd, includeCsd = true)

        val stream = StreamedPayloadInputStream()
        stream.write(payloadBuf, prefix = AnnexBUtils.START_CODE, prefixLen = AnnexBUtils.START_CODE.size)
        stream.finish()

        val out = ByteArray(reference.size)
        var off = 0
        while (off < out.size) {
            val n = stream.read(out, off, out.size - off)
            if (n == -1) break
            off += n
        }
        assertArrayEquals(reference, out.copyOf(off))
        stream.close()
    }
}

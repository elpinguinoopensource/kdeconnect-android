/*
 * SPDX-FileCopyrightText: 2026 Alfredo Medrano Sanchez <alfredomedranosanchez@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.camera

import java.nio.ByteBuffer

/**
 * Pure-JVM utilities for converting MediaCodec output buffers into self-contained
 * Annex-B byte streams suitable for the host-side ffmpeg h264 demuxer.
 *
 * **Rationale:** MediaCodec output buffers are length-prefixed or raw NALs
 * depending on vendor; the host-side ffmpeg h264 demuxer needs Annex-B start
 * codes, and SPS/PPS (CSD) must be resent before every IDR frame or the
 * decoder cannot start mid-stream.
 */
internal object AnnexBUtils {

    /** The 4-byte Annex-B start code `00 00 00 01`. */
    val START_CODE: ByteArray = byteArrayOf(0, 0, 0, 1)

    /**
     * Extract the NAL unit type from a NAL header byte.
     *
     * @param data byte array containing the NAL data
     * @param offset offset of the NAL header byte in [data]
     * @return the NAL unit type (lower 5 bits of the header byte)
     */
    fun nalType(data: ByteArray, offset: Int): Int {
        return data[offset].toInt() and 0x1F
    }

    /**
     * Check whether a [ByteBuffer] already contains Annex-B formatted data
     * (starts with `00 00 00 01`).
     *
     * Some Exynos/MediaTek encoders emit Annex-B CSD instead of raw SPS/PPS.
     * This check is non-destructive: the buffer's position is restored after
     * peeking.
     *
     * @param csd the CSD buffer to check
     * @return `true` if the buffer starts with the Annex-B start code
     */
    fun isByteBufferMode(csd: ByteBuffer): Boolean {
        if (csd.remaining() < 4) return false
        val saved = csd.position()
        return try {
            csd.get() == 0.toByte() &&
                csd.get() == 0.toByte() &&
                csd.get() == 0.toByte() &&
                csd.get() == 1.toByte()
        } finally {
            csd.position(saved)
        }
    }

    /**
     * Compute how many bytes [toAnnexB] would prepend for the given CSD set:
     * one Annex-B start code plus the blob for every raw CSD buffer, or just
     * the blob for buffers that already carry start codes (vendor Annex-B
     * mode). Returns 0 when no CSD should be prepended ([includeCsd] false,
     * [csd] null/empty).
     *
     * This mirrors [toAnnexB]'s internal size calculation exactly, so callers
     * (e.g. CameraSession's zero-copy fast path) can size their scratch buffer
     * up front without copying the payload first. Note that [toAnnexB] still
     * applies its own SPS-already-bundled check on the payload bytes; callers
     * that want to skip the prefix entirely should use
     * [firstNalIsSps] (ByteBuffer overload) instead of copying.
     *
     * @param csd list of CSD buffers (SPS, PPS); may be `null`
     * @param includeCsd whether CSD should be prepended at all
     * @param payload the Annex-B payload; used for the SPS-already-bundled
     *   check, kept for signature symmetry (see [toAnnexB] which uses it)
     * @return number of bytes [toAnnexB] would write before the payload start
     *   code, or 0 when CSD must not be prepended
     */
    fun csdPrependSize(csd: List<ByteBuffer>?, includeCsd: Boolean, payload: ByteArray): Int {
        val needCsd = includeCsd &&
            csd != null &&
            csd.isNotEmpty() &&
            !bufferAlreadyContainsSps(payload)
        return if (needCsd) csdPrependSize(csd) else 0
    }

    /**
     * [csdPrependSize] variant that checks for a bundled SPS by peeking at the
     * encoder output [buffer] directly (via [firstNalIsSps]) instead of a
     * copied payload array — used by CameraSession's zero-copy fast path to
     * size its scratch buffer without touching the payload bytes.
     */
    fun csdPrependSize(csd: List<ByteBuffer>?, includeCsd: Boolean, buffer: ByteBuffer): Int {
        val needCsd = includeCsd &&
            csd != null &&
            csd.isNotEmpty() &&
            !firstNalIsSps(buffer)
        return if (needCsd) csdPrependSize(csd) else 0
    }

    /**
     * Sum of the CSD bytes [toAnnexB] would prepend (start codes included),
     * without any payload-based SPS check. This is the single source of truth
     * for the size computation shared by [toAnnexB] and the public overload.
     */
    private fun csdPrependSize(csd: List<ByteBuffer>): Int {
        var total = 0
        for (csdBuf in csd) {
            total += if (isByteBufferMode(csdBuf)) {
                csdBuf.remaining() // already has start codes
            } else {
                START_CODE.size + csdBuf.remaining()
            }
        }
        return total
    }

    /**
     * Check whether the data in [buffer] (from its position to its limit)
     * already starts with an SPS NAL (type 7), either raw or after a 4-byte
     * Annex-B start code. Non-destructive: position and limit are restored.
     *
     * Lets CameraSession decide whether CSD prepending is needed by peeking
     * at the encoder output buffer directly, without copying the payload.
     */
    fun firstNalIsSps(buffer: ByteBuffer): Boolean {
        val n = buffer.remaining()
        if (n < 5) return false
        val pos = buffer.position()
        val limit = buffer.limit()
        return try {
            // Absolute get() is index-based on the backing array or a
            // bounds-checked access for direct buffers; it never moves
            // position, so peeking the first 5 bytes is safe.
            if (buffer.get(pos) == 0.toByte() &&
                buffer.get(pos + 1) == 0.toByte() &&
                buffer.get(pos + 2) == 0.toByte() &&
                buffer.get(pos + 3) == 1.toByte()
            ) {
                (buffer.get(pos + 4).toInt() and 0x1F) == 7
            } else {
                (buffer.get(pos).toInt() and 0x1F) == 7
            }
        } finally {
            buffer.position(pos)
            buffer.limit(limit)
        }
    }

    /**
     * Build a self-contained Annex-B access unit from a MediaCodec output
     * buffer and optional Codec-Specific Data (SPS/PPS).
     *
     * If [includeCsd] is `true` and [csd] is non-null, the CSD blobs are
     * prepended with start codes — but only if the buffer doesn't already
     * contain SPS (NAL type 7) as its first NAL. This avoids double-emitting
     * SPS/PPS when the encoder already bundles them (some Exynos/MediaTek
     * encoders do this for IDR frames).
     *
     * @param buffer the MediaCodec output buffer (data from [position] to [limit])
     * @param csd list of CSD buffers (SPS, PPS); may be `null`
     * @param includeCsd whether to prepend CSD data
     * @return a ByteArray containing the complete Annex-B access unit
     */
    fun toAnnexB(buffer: ByteBuffer, csd: List<ByteBuffer>?, includeCsd: Boolean): ByteArray {
        val payloadBytes = buffer.remaining()
        val payload = ByteArray(payloadBytes)
        val savedPos = buffer.position()
        buffer.get(payload)
        buffer.position(savedPos) // non-destructive

        // Determine if we need to prepend CSD
        val needCsd = includeCsd &&
            csd != null &&
            csd.isNotEmpty() &&
            !bufferAlreadyContainsSps(payload)

        // Calculate total size (shared helper keeps this in sync with
        // csdPrependSize() used by the zero-copy producer path)
        var totalSize = START_CODE.size + payloadBytes // start code + payload
        if (needCsd) {
            totalSize += csdPrependSize(csd!!)
        }

        val result = ByteArray(totalSize)
        var pos = 0

        // Write CSD with start codes if needed
        if (needCsd) {
            for (csdBuf in csd!!) {
                if (isByteBufferMode(csdBuf)) {
                    // Already Annex-B formatted, copy as-is
                    val saved = csdBuf.position()
                    csdBuf.get(result, pos, csdBuf.remaining())
                    csdBuf.position(saved)
                    pos += csdBuf.remaining()
                } else {
                    // Raw CSD — prepend start code
                    System.arraycopy(START_CODE, 0, result, pos, START_CODE.size)
                    pos += START_CODE.size
                    val saved = csdBuf.position()
                    csdBuf.get(result, pos, csdBuf.remaining())
                    csdBuf.position(saved)
                    pos += csdBuf.remaining()
                }
            }
        }

        // Write payload with start code
        System.arraycopy(START_CODE, 0, result, pos, START_CODE.size)
        pos += START_CODE.size
        System.arraycopy(payload, 0, result, pos, payloadBytes)

        return result
    }

    /**
     * Check if the payload already contains an SPS NAL (type 7) as its first NAL.
     * This is used to avoid double-prepending CSD when the encoder already
     * bundles SPS/PPS before IDR frames.
     */
    private fun bufferAlreadyContainsSps(payload: ByteArray): Boolean {
        if (payload.size < 5) return false
        // Check if first NAL is preceded by a start code and is type 7 (SPS)
        // or if the first byte directly is a NAL header with type 7
        return if (payload.size >= 4 &&
            payload[0] == 0.toByte() &&
            payload[1] == 0.toByte() &&
            payload[2] == 0.toByte() &&
            payload[3] == 1.toByte()
        ) {
            nalType(payload, 4) == 7
        } else {
            nalType(payload, 0) == 7
        }
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Alfredo Medrano Sanchez <alfredomedranosanchez@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.camera

import java.io.InputStream

/**
 * An [InputStream] adapter that bridges an open-ended producer/consumer stream
 * so it can be pumped by `LanLink.sendPayload()`.
 *
 * `LanLink.sendPayload()` loops:
 * ```java
 * while (!np.isCanceled() && (bytesRead = inputStream.read(buffer)) != -1) { ... }
 * ```
 * This class satisfies that contract: it blocks on `read()` until data is
 * available, and returns `-1` only after [finish] has been called **and** the
 * internal buffer is fully drained.
 *
 * ## Thread safety
 *
 * All public methods are `synchronized` on `this`. The producer thread calls
 * [write] (and eventually [finish]); the consumer thread calls [read].
 * [close] may be called from any thread (e.g. LanLink's `finally` block).
 *
 * ## Back-pressure (drop-oldest live semantics)
 *
 * [write] **never blocks** the producer. When the internal buffer would exceed
 * [maxBufferedBytes], the oldest queued chunks are dropped until the new chunk
 * fits — or if only one chunk remains, it is kept (deadlock avoidance for
 * oversized single writes into an empty queue). This ensures the MediaCodec
 * callback thread is never stalled, so the stream always delivers the newest
 * frames with bounded latency.
 *
 * Latency bound ≈ `maxBufferedBytes / realBitrate` (e.g. ≈1.5 s at 4 Mbps
 * with the default 768 KB cap).
 *
 * Each [write] call carries one complete MediaCodec output buffer in Annex-B
 * form (keyframes include CSD), so drops always land on NAL-unit boundaries —
 * no partial-NAL corruption is possible.
 *
 * Diagnostic counters [droppedBytes] and [droppedChunks] track cumulative
 * drops and are never reset (even on [finish]/[close]).
 *
 * ## Timeout semantics
 *
 * [read] blocks until data arrives, [finish] is called, or [close] is called.
 * The [timeoutMillis] parameter controls how often the blocking loop
 * re-checks the [isFinished] flag so that [finish] takes effect within one
 * timeout period without needing a thread interrupt. `read()` **never**
 * returns `0` when `len > 0` — that would violate `InputStream` contract.
 *
 * ## Chunk-queue design
 *
 * Internally data is stored as a queue of byte-array chunks (`ArrayDeque`).
 * Each [write] call enqueues one chunk. Reads consume from the head chunk
 * and pop it when fully drained. This avoids copying the entire buffer on
 * every read (which would be O(n²) for large buffers).
 *
 * @param timeoutMillis How often (ms) the read loop re-checks [isFinished].
 *   Default 200 ms.
 * @param maxBufferedBytes Maximum bytes buffered before the oldest data is
 *   dropped. Default 128 KB — this queue is pure added latency (≈230 ms at
 *   4 Mbps), so it is kept deliberately short: stale frames are dropped fast
 *   and the encoder is asked for a keyframe (see CameraSession) to
 *   resynchronise the consumer. Exposed for stall-detection diagnostics.
 */
class StreamedPayloadInputStream(
    private val timeoutMillis: Long = 200,
    val maxBufferedBytes: Int = 128 * 1024,
) : InputStream() {

    private val queue = ArrayDeque<ByteArray>()
    private var headChunkOffset = 0   // offset into the head chunk
    private var queuedBytes = 0       // total bytes in queue
    private var finished = false
    private var closed = false

    private var _droppedBytes: Long = 0
    private var _droppedChunks: Int = 0

    /** Number of bytes currently buffered (written but not yet read). */
    @get:Synchronized
    val bufferedBytes: Int
        get() = queuedBytes

    /** Whether [finish] or [close] has been called. */
    @get:Synchronized
    val isFinished: Boolean
        get() = finished

    /** True after close() — the LanLink pump thread is gone or going. */
    @get:Synchronized
    val isClosed: Boolean get() = closed

    /** Cumulative bytes dropped by the drop-oldest backpressure policy. Never reset. */
    @get:Synchronized
    val droppedBytes: Long
        get() = _droppedBytes

    /** Cumulative number of chunks dropped by the drop-oldest backpressure policy. Never reset. */
    @get:Synchronized
    val droppedChunks: Int
        get() = _droppedChunks

    /**
     * Producer side: enqueue [length] bytes starting at [offset] in [data].
     * **Never blocks.** Uses drop-oldest backpressure: when the buffer would
     * exceed [maxBufferedBytes], the oldest queued chunks are popped until the
     * new chunk fits (or only one chunk remains — deadlock avoidance for
     * oversized writes into an empty queue).
     *
     * Each write() call carries one complete MediaCodec output buffer in
     * Annex-B form, so drops always land on NAL-unit boundaries.
     *
     * If the stream has already been finished or closed, this method returns
     * silently (0 dropped). This is safe because LanLink may close the payload
     * from the pump thread while the MediaCodec callback thread is still
     * calling write().
     *
     * @return number of chunks dropped to make room (0 when nothing was
     *   dropped). Callers use this to trigger a keyframe request so the
     *   consumer can resynchronise immediately after a gap.
     */
    @Synchronized
    fun write(data: ByteArray, offset: Int, length: Int): Int {
        if (finished || closed) return 0

        // Drop-oldest: pop head chunks until the new chunk fits or only 1 remains.
        // A single oversized chunk into an EMPTY queue is still enqueued (deadlock avoidance).
        var dropped = 0
        while (queuedBytes + length > maxBufferedBytes && queue.size > 1) {
            val head = queue.removeFirst()
            val lost = head.size - headChunkOffset
            queuedBytes -= lost
            _droppedBytes += lost
            _droppedChunks++
            dropped++
            headChunkOffset = 0
        }
        if (closed) return 0

        val chunk = ByteArray(length)
        System.arraycopy(data, offset, chunk, 0, length)
        queue.addLast(chunk)
        queuedBytes += length
        (this as Object).notifyAll()
        return dropped
    }

    /**
     * Consumer side: read a single byte. Blocks until data is available or
     * the stream is finished/closed.
     *
     * @return the next byte as an `int` in `[0..255]`, or `-1` if the stream
     *   is finished and the buffer is drained.
     */
    @Synchronized
    override fun read(): Int {
        while (true) {
            if (queuedBytes > 0) {
                val head = queue.first()
                val b = head[headChunkOffset].toInt() and 0xFF
                headChunkOffset++
                queuedBytes--
                if (headChunkOffset >= head.size) {
                    queue.removeFirst()
                    headChunkOffset = 0
                }
                (this as Object).notifyAll() // signal back-pressure relief
                return b
            }
            if (finished || closed) return -1
            // Wait for data or finish signal
            (this as Object).wait(timeoutMillis)
        }
    }

    /**
     * Consumer side: read up to [len] bytes into [b] starting at [off].
     * Blocks until at least 1 byte is available or the stream is finished/closed.
     *
     * **Never returns 0 when `len > 0`** — that would violate `InputStream`
     * contract. On timeout with the stream still open, keeps waiting.
     *
     * @return number of bytes read, or `-1` if the stream is finished and
     *   the buffer is drained.
     */
    @Synchronized
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        require(len >= 0) { "len < 0" }
        if (len == 0) return 0

        while (true) {
            if (queuedBytes > 0) {
                var remaining = len.coerceAtMost(queuedBytes)
                val copied = remaining
                var destPos = off
                while (remaining > 0 && queue.isNotEmpty()) {
                    val head = queue.first()
                    val available = head.size - headChunkOffset
                    val toCopy = minOf(available, remaining)
                    System.arraycopy(head, headChunkOffset, b, destPos, toCopy)
                    headChunkOffset += toCopy
                    destPos += toCopy
                    remaining -= toCopy
                    queuedBytes -= toCopy
                    if (headChunkOffset >= head.size) {
                        queue.removeFirst()
                        headChunkOffset = 0
                    }
                }
                (this as Object).notifyAll() // signal back-pressure relief
                return copied
            }
            if (finished || closed) return -1
            // Wait for data or finish signal — never return 0
            (this as Object).wait(timeoutMillis)
        }
    }

    /**
     * Producer signals end of stream. Idempotent.
     * Unblocks waiting readers so their next `read` returns `-1` once the
     * buffer is drained.
     */
    @Synchronized
    fun finish() {
        if (finished) return
        finished = true
        (this as Object).notifyAll()
    }

    /**
     * Calls [finish] and clears the buffer.
     * LanLink calls `payload.close()` in its `finally` block.
     */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        finished = true
        queue.clear()
        queuedBytes = 0
        headChunkOffset = 0
        (this as Object).notifyAll()
    }
}

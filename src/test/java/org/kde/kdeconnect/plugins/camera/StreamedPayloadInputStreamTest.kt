/*
 * SPDX-FileCopyrightText: 2026 Alfredo Medrano Sanchez <alfredomedranosanchez@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class StreamedPayloadInputStreamTest {

    @Test
    fun writeThenReadReturnsSameBytes() {
        val stream = StreamedPayloadInputStream()
        val data = byteArrayOf(1, 2, 3, 4, 5)
        stream.write(data, 0, data.size)
        stream.finish()

        val out = ByteArray(5)
        val n = stream.read(out, 0, 5)
        assertEquals(5, n)
        assertArrayEquals(data, out)

        // Next read should return -1 (finished + drained)
        assertEquals(-1, stream.read(out, 0, 5))
        stream.close()
    }

    @Test
    fun multiWriteMultiReadPreservesByteOrder() {
        val stream = StreamedPayloadInputStream()

        val totalSize = 100 * 1024 // 100 KB
        val sourceData = ByteArray(totalSize) { (it and 0xFF).toByte() }

        val producerDone = CountDownLatch(1)
        val consumerDone = CountDownLatch(1)
        val error = AtomicReference<Throwable>(null)

        // Producer: write in 1 KB chunks
        val producer = Thread {
            try {
                var offset = 0
                val chunkSize = 1024
                while (offset < totalSize) {
                    val len = minOf(chunkSize, totalSize - offset)
                    stream.write(sourceData, offset, len)
                    offset += len
                }
                stream.finish()
                producerDone.countDown()
            } catch (e: Throwable) {
                error.compareAndSet(null, e)
                producerDone.countDown()
            }
        }

        // Consumer: read in 777-byte chunks
        val result = ByteArray(totalSize)
        var resultOffset = 0
        val consumer = Thread {
            try {
                val buf = ByteArray(777)
                while (true) {
                    val n = stream.read(buf, 0, buf.size)
                    if (n == -1) break
                    System.arraycopy(buf, 0, result, resultOffset, n)
                    resultOffset += n
                }
                consumerDone.countDown()
            } catch (e: Throwable) {
                error.compareAndSet(null, e)
                consumerDone.countDown()
            }
        }

        producer.start()
        consumer.start()

        assertTrue("Producer timed out", producerDone.await(10, TimeUnit.SECONDS))
        assertTrue("Consumer timed out", consumerDone.await(10, TimeUnit.SECONDS))

        val err = error.get()
        if (err != null) throw AssertionError("Thread error", err)

        assertEquals(totalSize, resultOffset)
        assertArrayEquals(sourceData, result)
        stream.close()
    }

    @Test
    fun finishUnblocksWaitingReader() {
        val stream = StreamedPayloadInputStream(timeoutMillis = 50)
        val latch = CountDownLatch(1)
        val readResult = AtomicInteger(-999)

        val reader = Thread {
            val buf = ByteArray(10)
            readResult.set(stream.read(buf, 0, 10))
            latch.countDown()
        }
        reader.start()

        // Give reader time to block
        Thread.sleep(100)
        stream.finish()

        assertTrue("Reader did not unblock", latch.await(5, TimeUnit.SECONDS))
        assertEquals(-1, readResult.get())
        stream.close()
    }

    @Test
    fun closeUnblocksWaitingReader() {
        val stream = StreamedPayloadInputStream(timeoutMillis = 50)
        val latch = CountDownLatch(1)
        val readResult = AtomicInteger(-999)

        val reader = Thread {
            val buf = ByteArray(10)
            readResult.set(stream.read(buf, 0, 10))
            latch.countDown()
        }
        reader.start()

        // Give reader time to block
        Thread.sleep(100)
        stream.close()

        assertTrue("Reader did not unblock", latch.await(5, TimeUnit.SECONDS))
        assertEquals(-1, readResult.get())
    }

    @Test
    fun dataWrittenBeforeFinishIsStillReadable() {
        val stream = StreamedPayloadInputStream()
        val data = byteArrayOf(10, 20, 30)
        stream.write(data, 0, data.size)
        stream.finish()

        // Read one byte at a time
        assertEquals(10, stream.read())
        assertEquals(20, stream.read())
        assertEquals(30, stream.read())
        // Now drained + finished => -1
        assertEquals(-1, stream.read())
        stream.close()
    }

    @Test
    fun backpressureDropsOldestInsteadOfBlocking() {
        val maxBuf = 1024
        val stream = StreamedPayloadInputStream(maxBufferedBytes = maxBuf)

        // Write two chunks that together exceed maxBufferedBytes
        val chunk1 = ByteArray(600) { 0x42 }
        val chunk2 = ByteArray(600) { 0x43 }
        stream.write(chunk1, 0, chunk1.size)  // queuedBytes = 600
        stream.write(chunk2, 0, chunk2.size)  // queuedBytes = 1200 (queue.size was 1, no drop)

        // Third write: queue.size == 2, so oldest chunk should be dropped
        val chunk3 = ByteArray(600) { 0x44 }
        stream.write(chunk3, 0, chunk3.size)  // drops chunk1, enqueues chunk3

        assertEquals("droppedBytes", 600L, stream.droppedBytes)
        assertEquals("droppedChunks", 1, stream.droppedChunks)

        // Drain and verify newest data (chunk2 + chunk3) is readable
        stream.finish()
        val result = ByteArray(1200)
        var offset = 0
        while (offset < result.size) {
            val n = stream.read(result, offset, result.size - offset)
            if (n == -1) break
            offset += n
        }
        assertEquals(1200, offset)
        // First 600 bytes = chunk2
        for (i in 0 until 600) assertEquals(0x43, result[i].toInt() and 0xFF)
        // Next 600 bytes = chunk3
        for (i in 600 until 1200) assertEquals(0x44, result[i].toInt() and 0xFF)
        stream.close()
    }

    @Test
    fun concurrentWriteAndCloseDoesNotHang() {
        val stream = StreamedPayloadInputStream(maxBufferedBytes = 256)
        val done = CountDownLatch(1)

        // Fill the buffer
        stream.write(ByteArray(256) { 1 }, 0, 256)

        val writer = Thread {
            try {
                // This may block due to backpressure; close() should unblock it
                stream.write(ByteArray(256) { 2 }, 0, 256)
            } catch (_: Throwable) {
                // IllegalStateException or interruption is fine
            }
            done.countDown()
        }
        writer.start()

        Thread.sleep(100) // let writer block
        stream.close()

        assertTrue("Writer did not finish within timeout (possible deadlock)",
            done.await(5, TimeUnit.SECONDS))
    }

    // ---- Regression tests for CAM-3b fixes ----

    @Test
    fun writeAfterCloseDoesNotThrow() {
        val stream = StreamedPayloadInputStream()
        stream.write(byteArrayOf(1, 2, 3), 0, 3)
        stream.close()

        // write() after close() must be a silent no-op, not throw
        stream.write(byteArrayOf(4, 5, 6), 0, 3)

        // buffer should be empty (close() cleared it)
        assertEquals(0, stream.bufferedBytes)
    }

    @Test
    fun writeAfterFinishDoesNotThrow() {
        val stream = StreamedPayloadInputStream()
        stream.write(byteArrayOf(1, 2, 3), 0, 3)
        stream.finish()

        // write() after finish() must be a silent no-op, not throw
        stream.write(byteArrayOf(4, 5, 6), 0, 3)

        // previously written data should still be readable
        assertEquals(3, stream.bufferedBytes)
        assertEquals(1, stream.read())
        assertEquals(2, stream.read())
        assertEquals(3, stream.read())
        assertEquals(-1, stream.read())
        stream.close()
    }

    @Test
    fun singleWriteLargerThanMaxBufferedBytesSucceedsWhenQueueEmpty() {
        val maxBuf = 1024
        val stream = StreamedPayloadInputStream(maxBufferedBytes = maxBuf)

        // A single write larger than maxBufferedBytes must succeed
        // when the queue is empty (no deadlock).
        val bigData = ByteArray(maxBuf * 2) { (it and 0xFF).toByte() }
        stream.write(bigData, 0, bigData.size)

        assertEquals(bigData.size, stream.bufferedBytes)

        // Consumer drains it
        val result = ByteArray(bigData.size)
        var offset = 0
        while (offset < result.size) {
            val n = stream.read(result, offset, result.size - offset)
            if (n == -1) break
            offset += n
        }
        assertEquals(bigData.size, offset)
        assertArrayEquals(bigData, result)
        stream.close()
    }

    @Test
    fun singleWriteLargerThanMaxBufferedBytesNoDeadlock() {
        // Variant: producer and consumer on separate threads to verify
        // the producer does not deadlock when queue is empty.
        val maxBuf = 1024
        val stream = StreamedPayloadInputStream(maxBufferedBytes = maxBuf)
        val bigData = ByteArray(maxBuf * 4) { (it and 0xFF).toByte() }

        val writeDone = CountDownLatch(1)
        val error = AtomicReference<Throwable>(null)

        val producer = Thread {
            try {
                stream.write(bigData, 0, bigData.size)
                stream.finish()
                writeDone.countDown()
            } catch (e: Throwable) {
                error.compareAndSet(null, e)
                writeDone.countDown()
            }
        }
        producer.start()

        // Consumer drains on main thread
        val result = ByteArray(bigData.size)
        var offset = 0
        while (offset < result.size) {
            val n = stream.read(result, offset, result.size - offset)
            if (n == -1) break
            offset += n
        }

        assertTrue("Producer deadlocked or timed out",
            writeDone.await(5, TimeUnit.SECONDS))

        val err = error.get()
        if (err != null) throw AssertionError("Producer error", err)

        assertEquals(bigData.size, offset)
        assertArrayEquals(bigData, result)
        stream.close()
    }

    @Test
    fun performanceSmokeEightMegabytes() {
        // Write+read 8 MB total in 4 KB chunks; must complete in < 2 s.
        // Catches O(n²) regressions like copying the full buffer per read.
        val stream = StreamedPayloadInputStream(maxBufferedBytes = 2 * 1024 * 1024)
        val totalBytes = 8 * 1024 * 1024
        val chunkSize = 4096
        val sourceData = ByteArray(chunkSize) { (it and 0xFF).toByte() }

        val producerDone = CountDownLatch(1)
        val error = AtomicReference<Throwable>(null)

        val producer = Thread {
            try {
                var written = 0
                while (written < totalBytes) {
                    val len = minOf(chunkSize, totalBytes - written)
                    stream.write(sourceData, 0, len)
                    written += len
                }
                stream.finish()
                producerDone.countDown()
            } catch (e: Throwable) {
                error.compareAndSet(null, e)
                producerDone.countDown()
            }
        }

        val consumerResult = AtomicInteger(0)
        val consumerDone = CountDownLatch(1)
        val consumer = Thread {
            try {
                val buf = ByteArray(4096)
                var total = 0
                while (true) {
                    val n = stream.read(buf, 0, buf.size)
                    if (n == -1) break
                    total += n
                }
                consumerResult.set(total)
                consumerDone.countDown()
            } catch (e: Throwable) {
                error.compareAndSet(null, e)
                consumerDone.countDown()
            }
        }

        val startNanos = System.nanoTime()
        producer.start()
        consumer.start()

        assertTrue("Producer timed out", producerDone.await(10, TimeUnit.SECONDS))
        assertTrue("Consumer timed out", consumerDone.await(10, TimeUnit.SECONDS))

        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

        val err = error.get()
        if (err != null) throw AssertionError("Thread error", err)

        // With drop-oldest, some data may be dropped if the consumer is slower
        // than the producer, but the test should complete fast.
        assertTrue("Performance regression: 8 MB took ${elapsedMs} ms (limit 2000 ms)",
            elapsedMs < 2000)
        stream.close()
    }

    // ---- Drop-oldest backpressure tests (CAM-11) ----

    @Test
    fun writeDropsOldestChunksWhenFull() {
        // Small max buffer, write 3 chunks of 600; verify drop-oldest behavior.
        val maxBuf = 1000
        val stream = StreamedPayloadInputStream(maxBufferedBytes = maxBuf)

        val chunkA = ByteArray(600) { 0x10 }
        val chunkB = ByteArray(600) { 0x20 }
        val chunkC = ByteArray(600) { 0x30 }

        stream.write(chunkA, 0, chunkA.size)  // queuedBytes = 600
        stream.write(chunkB, 0, chunkB.size)  // queuedBytes = 1200 (queue.size=1, no drop)
        stream.write(chunkC, 0, chunkC.size)  // drops A, enqueues C; queuedBytes = 1200

        // No thread ever blocked — write() completed instantly on main thread
        assertTrue("droppedBytes >= 600", stream.droppedBytes >= 600)
        assertTrue("droppedChunks >= 1", stream.droppedChunks >= 1)

        // Drain: should contain B + C (newest data)
        stream.finish()
        val result = ByteArray(1200)
        var offset = 0
        while (offset < result.size) {
            val n = stream.read(result, offset, result.size - offset)
            if (n == -1) break
            offset += n
        }
        assertEquals(1200, offset)
        // First 600 = chunkB
        for (i in 0 until 600) assertEquals(0x20, result[i].toInt() and 0xFF)
        // Next 600 = chunkC
        for (i in 600 until 1200) assertEquals(0x30, result[i].toInt() and 0xFF)
        stream.close()
    }

    @Test
    fun singleOversizedChunkStillEnqueued() {
        // max=100, write 500 into empty queue → must succeed (deadlock avoidance).
        val maxBuf = 100
        val stream = StreamedPayloadInputStream(maxBufferedBytes = maxBuf)

        val bigData = ByteArray(500) { (it and 0xFF).toByte() }
        stream.write(bigData, 0, bigData.size)

        assertEquals(500, stream.bufferedBytes)
        assertEquals(0L, stream.droppedBytes)

        // Drain and verify content
        stream.finish()
        val result = ByteArray(500)
        var offset = 0
        while (offset < result.size) {
            val n = stream.read(result, offset, result.size - offset)
            if (n == -1) break
            offset += n
        }
        assertEquals(500, offset)
        assertArrayEquals(bigData, result)
        stream.close()
    }

    @Test
    fun dropPreservesByteOrderOfKeptData() {
        // Write A, B, C (distinct patterns), then D, E to force drops.
        // Drain and assert remaining stream is a contiguous suffix.
        val maxBuf = 1000
        val stream = StreamedPayloadInputStream(maxBufferedBytes = maxBuf)

        val chunkA = ByteArray(400) { 0xAA.toByte() }
        val chunkB = ByteArray(400) { 0xBB.toByte() }
        val chunkC = ByteArray(400) { 0xCC.toByte() }
        val chunkD = ByteArray(400) { 0xDD.toByte() }
        val chunkE = ByteArray(400) { 0xEE.toByte() }

        stream.write(chunkA, 0, chunkA.size)  // queuedBytes = 400
        stream.write(chunkB, 0, chunkB.size)  // queuedBytes = 800
        stream.write(chunkC, 0, chunkC.size)  // drops A, queuedBytes = 800
        stream.write(chunkD, 0, chunkD.size)  // drops B, queuedBytes = 800
        stream.write(chunkE, 0, chunkE.size)  // drops C, queuedBytes = 800

        assertEquals(1200L, stream.droppedBytes)  // A + B + C = 1200
        assertEquals(3, stream.droppedChunks)

        // Drain: should be D + E (contiguous suffix)
        stream.finish()
        val result = ByteArray(800)
        var offset = 0
        while (offset < result.size) {
            val n = stream.read(result, offset, result.size - offset)
            if (n == -1) break
            offset += n
        }
        assertEquals(800, offset)
        // First 400 = D
        for (i in 0 until 400) assertEquals(0xDD, result[i].toInt() and 0xFF)
        // Next 400 = E
        for (i in 400 until 800) assertEquals(0xEE, result[i].toInt() and 0xFF)
        stream.close()
    }

    // ---- ByteBuffer zero-copy fast-path tests (camera latency tuning) ----

    /** Drain a finished stream into a byte array (read() until -1). */
    private fun drain(stream: StreamedPayloadInputStream, expected: Int? = null): ByteArray {
        val out = java.io.ByteArrayOutputStream(expected ?: 64)
        val buf = ByteArray(997) // odd size: exercises partial chunk reads
        while (true) {
            val n = stream.read(buf, 0, buf.size)
            if (n == -1) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    fun writeByteBufferMatchesByteArrayPath() {
        // Same logical chunk via both paths must produce byte-identical output.
        val payload = ByteArray(500) { ((it * 7 + 3) and 0xFF).toByte() }
        val prefix = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x1E)

        // Reference: classic ByteArray path (prefix + payload concatenated).
        val reference = StreamedPayloadInputStream()
        val joined = prefix + payload
        reference.write(joined, 0, joined.size)
        reference.finish()

        // Fast path: ByteBuffer payload + ByteArray prefix, one chunk.
        val fast = StreamedPayloadInputStream()
        val src = ByteBuffer.wrap(payload.copyOf())
        val dropped = fast.write(src, prefix = prefix, prefixLen = prefix.size)
        assertEquals(0, dropped)
        fast.finish()

        assertArrayEquals(drain(reference, joined.size), drain(fast, joined.size))
        reference.close()
        fast.close()

        // And the no-prefix variant against a payload-only ByteArray write.
        val reference2 = StreamedPayloadInputStream()
        reference2.write(payload, 0, payload.size)
        reference2.finish()

        val fast2 = StreamedPayloadInputStream()
        fast2.write(ByteBuffer.wrap(payload.copyOf()), prefix = null, prefixLen = 0)
        fast2.finish()

        assertArrayEquals(drain(reference2, payload.size), drain(fast2, payload.size))
    }

    @Test
    fun writeByteBufferPartialPrefixHonorsPrefixLen() {
        // prefixLen < prefix.size must take only the first prefixLen bytes.
        val stream = StreamedPayloadInputStream()
        val prefix = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val payload = byteArrayOf(0x65, 0x11, 0x22)

        stream.write(ByteBuffer.wrap(payload), prefix = prefix, prefixLen = 3)
        stream.finish()

        assertArrayEquals(
            byteArrayOf(1, 2, 3, 0x65, 0x11, 0x22),
            drain(stream, 6),
        )
        stream.close()
    }

    @Test
    fun writeByteBufferRespectsSourcePositionAndLimit() {
        // Only [position, limit) is read; both are left untouched.
        val raw = byteArrayOf(0x55, 0x55, 0x01, 0x02, 0x03, 0x04, 0x77, 0x77)
        val buf = ByteBuffer.wrap(raw)
        buf.position(2)
        buf.limit(6)

        val stream = StreamedPayloadInputStream()
        stream.write(buf, prefix = null, prefixLen = 0)
        stream.finish()

        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), drain(stream, 4))
        assertEquals("position must not change", 2, buf.position())
        assertEquals("limit must not change", 6, buf.limit())
        stream.close()
    }

    @Test
    fun writeByteBufferDropOldestMatchesByteArrayPath() {
        // Identical backpressure behaviour: three 600-byte chunks into a
        // 1000-byte buffer drop the oldest, counters and order included.
        val maxBuf = 1000
        val bbStream = StreamedPayloadInputStream(maxBufferedBytes = maxBuf)
        val baStream = StreamedPayloadInputStream(maxBufferedBytes = maxBuf)

        val chunkA = ByteArray(600) { 0x10 }
        val chunkB = ByteArray(600) { 0x20 }
        val chunkC = ByteArray(600) { 0x30 }

        for (chunk in listOf(chunkA, chunkB, chunkC)) {
            val droppedBa = baStream.write(chunk, 0, chunk.size)
            val droppedBb = bbStream.write(
                ByteBuffer.wrap(chunk.copyOf()),
                prefix = null,
                prefixLen = 0,
            )
            // Same drop decision on both paths, iteration by iteration.
            assertEquals("drop count mismatch for chunk ${chunk[0]}", droppedBa, droppedBb)
        }

        assertEquals(baStream.droppedChunks, bbStream.droppedChunks)
        assertEquals(baStream.droppedBytes, bbStream.droppedBytes)
        assertEquals(1, bbStream.droppedChunks)
        assertEquals(600L, bbStream.droppedBytes)

        // Kept data must be B + C in order (newest suffix), same as ByteArray path.
        baStream.finish()
        bbStream.finish()
        val expected = chunkB + chunkC
        assertArrayEquals(expected, drain(baStream, expected.size))
        assertArrayEquals(expected, drain(bbStream, expected.size))
        baStream.close()
        bbStream.close()
    }

    @Test
    fun oversizedByteBufferChunkEnqueuedWhenQueueEmpty() {
        // A chunk larger than maxBufferedBytes into an EMPTY queue is still
        // enqueued (deadlock avoidance), exactly like the ByteArray path.
        val maxBuf = 1024
        val stream = StreamedPayloadInputStream(maxBufferedBytes = maxBuf)
        val payload = ByteArray(maxBuf * 3) { (it and 0xFF).toByte() }
        val prefix = byteArrayOf(0, 0, 0, 1)

        val dropped = stream.write(ByteBuffer.wrap(payload), prefix = prefix, prefixLen = prefix.size)
        assertEquals(0, dropped)
        assertEquals(payload.size + prefix.size, stream.bufferedBytes)

        stream.finish()
        val out = drain(stream, payload.size + prefix.size)
        assertArrayEquals(prefix + payload, out)
        stream.close()
    }

    @Test
    fun writeByteBufferAfterFinishOrCloseReturnsZero() {
        // After finish(): silent no-op, returns 0, existing data readable.
        val finished = StreamedPayloadInputStream()
        finished.write(byteArrayOf(1, 2, 3), 0, 3)
        finished.finish()
        assertEquals(0, finished.write(ByteBuffer.wrap(byteArrayOf(4, 5, 6))))
        assertEquals(3, finished.bufferedBytes)
        assertArrayEquals(byteArrayOf(1, 2, 3), drain(finished, 3))
        finished.close()

        // After close(): silent no-op, buffer stays empty.
        val closed = StreamedPayloadInputStream()
        closed.write(byteArrayOf(1, 2, 3), 0, 3)
        closed.close()
        assertEquals(0, closed.write(ByteBuffer.wrap(byteArrayOf(4, 5, 6)), prefix = byteArrayOf(9), prefixLen = 1))
        assertEquals(0, closed.bufferedBytes)
    }

    @Test
    fun writeByteBufferEmptyPayloadWithPrefixEnqueuesPrefixOnly() {
        // Edge case: remaining() == 0 (e.g. zero-length output buffer).
        val stream = StreamedPayloadInputStream()
        val prefix = byteArrayOf(0, 0, 0, 1)
        val dropped = stream.write(ByteBuffer.allocate(0), prefix = prefix, prefixLen = prefix.size)
        assertEquals(0, dropped)
        assertEquals(4, stream.bufferedBytes)
        stream.finish()
        assertArrayEquals(prefix, drain(stream, 4))
        stream.close()
    }
}

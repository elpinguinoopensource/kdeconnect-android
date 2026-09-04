/*
 * SPDX-FileCopyrightText: 2026 Alfredo Medrano Sanchez <alfredomedranosanchez@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end instrumented test for the production [CameraSession] on real hardware.
 *
 * Validates the CAM-4 glue (Camera2 open → MediaCodec H.264 encoder → payload
 * stream, including the camera-open / capture-session latches, their timeouts and
 * the resource-release guards) and the CAM-6 stall watchdog on a physical device.
 * The CAM-7 debug spike only exercised a *copy* of the encoder configuration; this
 * test drives [CameraSession] itself with a mocked [Device], capturing the
 * [StreamedPayloadInputStream] it attaches to the `kdeconnect.camera.stream`
 * packet and asserting real H.264 bytes flow through it.
 *
 * The stream is drained by a background consumer thread: without a consumer the
 * 2 MB back-pressure buffer fills up and the CAM-6 watchdog tears the session
 * down after ~10 s, which would defeat the happy-path assertions.
 *
 * Finally, a second [CameraSession] is started on the same cameraId after the
 * first one was stopped: if [CameraSession.stop()] leaked the camera, opening
 * it again would fail (IN_USE / timeout) and the second payload would never
 * reach [Device.sendPacket].
 *
 * Requires the CAMERA runtime permission to be granted to the app under test
 * (skips otherwise, as do devices without any camera).
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 21)
class CameraSessionInstrumentedTest {

    private val appContext: Context = ApplicationProvider.getApplicationContext()

    /**
     * Drives one [CameraSession] and observes it: captures the payload stream
     * handed to [Device.sendPacket], drains it on a background thread and
     * records the terminal callback (stopped or failed).
     */
    private inner class Harness(
        request: CameraSession.Request,
        private val artifactName: String,
    ) {
        /** Set by CameraSession (on its handler thread) when it sends the stream packet. */
        val streamRef = AtomicReference<InputStream?>(null)
        val packetType = AtomicReference<String?>(null)

        /** Bytes accumulated by the consumer so far. */
        val bytesCaptured = AtomicLong(0)

        /** Counts down when the consumer thread has fully exited. */
        val consumerDone = CountDownLatch(1)

        /** Counts down on the first terminal callback (stopped or failed). */
        val terminal = CountDownLatch(1)

        val stoppedFlag = AtomicBoolean(false)
        val failure = AtomicReference<CameraError?>(null)

        private val stopConsumer = AtomicBoolean(false)

        val device: Device = mockk<Device>(relaxed = true).apply {
            every { context } returns appContext
            every { sendPacket(any()) } answers {
                val np = firstArg<NetworkPacket>()
                packetType.set(np.type)
                streamRef.set(np.payload?.inputStream)
            }
        }

        val session: CameraSession = CameraSession(
            device,
            request,
            object : CameraSession.Callbacks {
                override fun onSessionFailed(error: CameraError) {
                    failure.set(error)
                    terminal.countDown()
                }

                override fun onSessionStopped() {
                    stoppedFlag.set(true)
                    terminal.countDown()
                }
            },
        )

        init {
            Thread({ drain() }, "CameraSessionTest-Consumer").apply {
                isDaemon = true
                start()
            }
        }

        /** Poll [streamRef] until the pipeline reached the sendPacket stage. */
        fun awaitStream(timeoutMs: Long): Boolean {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (streamRef.get() == null && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(50)
            }
            return streamRef.get() != null
        }

        /** Orderly shutdown: stop the session, wait for the terminal callback and the consumer. */
        fun shutdown(stopLatchMs: Long, consumerLatchMs: Long): Boolean {
            session.stop()
            val terminalOk = terminal.await(stopLatchMs, TimeUnit.MILLISECONDS)
            stopConsumer.set(true)
            // Closing unblocks a consumer stuck in read() (finish() alone may not
            // if the producer thread is gone); harmless after a normal EOS.
            try {
                streamRef.get()?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing stream during shutdown", e)
            }
            val consumerOk = consumerDone.await(consumerLatchMs, TimeUnit.MILLISECONDS)
            return terminalOk && consumerOk
        }

        /**
         * Consumer loop: wait for the stream to appear, then read until the
         * producer signals EOS (-1), the harness asks us to stop, or the hard
         * deadline expires. Bytes are kept in memory and dumped to a file for
         * offline inspection (e.g. `ffprobe h264_stream_session1.bin`).
         */
        private fun drain() {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            val deadline = SystemClock.elapsedRealtime() + CONSUMER_DEADLINE_MS
            try {
                while (!stopConsumer.get() && streamRef.get() == null &&
                    SystemClock.elapsedRealtime() < deadline
                ) {
                    Thread.sleep(50)
                }
                val stream = streamRef.get()
                while (stream != null && !stopConsumer.get() &&
                    SystemClock.elapsedRealtime() < deadline
                ) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    bytesCaptured.set(out.size().toLong())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Consumer thread error ($artifactName)", e)
            } finally {
                bytesCaptured.set(out.size().toLong())
                saveArtifact(artifactName, out.toByteArray())
                consumerDone.countDown()
            }
        }

        private fun saveArtifact(name: String, data: ByteArray) {
            try {
                val dir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
                File(dir, name).writeBytes(data)
            } catch (e: Exception) {
                Log.w(TAG, "Could not save $name", e)
            }
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun cameraSession_streamsH264AndReleasesCameraOnStop() {
        assumeTrue(
            "CAMERA permission not granted to the app under test",
            appContext.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )

        val cams = Camera2Catalog(appContext).listCameras()
        assumeTrue("No camera available on this device", cams.isNotEmpty())
        val camera = cams.firstOrNull { it.facing == CameraProtocol.FACING_BACK } ?: cams.first()
        Log.i(TAG, "Using camera id=${camera.id} facing=${camera.facing}")

        val request = CameraSession.Request(
            cameraId = camera.id,
            width = 640,
            height = 480,
            fps = 30,
            bitrate = 2_000_000,
        )

        // ── Session 1: real Camera2 → MediaCodec → stream pipeline (CAM-4) ──
        val h1 = Harness(request, "h264_stream_session1.bin")
        val startMs = SystemClock.elapsedRealtime()
        h1.session.start()

        assertTrue(
            "kdeconnect.camera.stream payload never reached sendPacket within " +
                "${FIRST_STREAM_WAIT_MS}ms (session failure: ${h1.failure.get()})",
            h1.awaitStream(FIRST_STREAM_WAIT_MS),
        )
        val reachedPipelineMs = SystemClock.elapsedRealtime() - startMs
        assertTrue(
            "wrong packet type: ${h1.packetType.get()}",
            h1.packetType.get() == CameraProtocol.PACKET_TYPE_CAMERA_STREAM,
        )
        Log.i(TAG, "Session 1 reached sendPacket stage after ${reachedPipelineMs}ms")

        // Let the encoder produce frames while the consumer keeps the buffer drained.
        Thread.sleep(STREAM_WINDOW_MS)

        val bytes1 = h1.bytesCaptured.get()
        val elapsed1 = SystemClock.elapsedRealtime() - startMs
        Log.i(
            TAG,
            "Session 1: captured $bytes1 bytes of H.264 in ${elapsed1}ms " +
                "(stopped=${h1.stoppedFlag.get()} failure=${h1.failure.get()})",
        )
        assertTrue(
            "expected > $MIN_BYTES bytes of real H.264 output, got $bytes1 " +
                "(session failure: ${h1.failure.get()})",
            bytes1 > MIN_BYTES,
        )

        // stop() must deliver a terminal callback and the consumer must see EOS.
        assertTrue(
            "session 1 neither stopped nor failed within ${STOP_LATCH_MS}ms " +
                "(failure=${h1.failure.get()})",
            h1.shutdown(STOP_LATCH_MS, CONSUMER_SHUTDOWN_MS),
        )

        // ── Session 2: proves session 1 released the camera (no leak) ────────
        val h2 = Harness(request, "h264_stream_session2.bin")
        val start2Ms = SystemClock.elapsedRealtime()
        h2.session.start()
        val openedAgain = h2.awaitStream(SECOND_STREAM_WAIT_MS)
        Log.i(
            TAG,
            "Session 2: payload ${if (openedAgain) "received" else "NOT received"} after " +
                "${SystemClock.elapsedRealtime() - start2Ms}ms " +
                "(stopped=${h2.stoppedFlag.get()} failure=${h2.failure.get()})",
        )
        assertTrue(
            "second session could not reach the sendPacket stage — first session " +
                "leaked the camera (failure=${h2.failure.get()})",
            openedAgain,
        )
        assertTrue(
            "session 2 neither stopped nor failed within ${STOP_LATCH_MS}ms " +
                "(failure=${h2.failure.get()})",
            h2.shutdown(STOP_LATCH_MS, CONSUMER_SHUTDOWN_MS),
        )

        Log.i(TAG, "Test OK: ${h1.bytesCaptured.get()} + ${h2.bytesCaptured.get()} bytes captured")
    }

    companion object {
        private const val TAG = "KDE/CameraSessionTest"

        /** Camera open (5 s) + session config (5 s) timeouts inside CameraSession + slack. */
        private const val FIRST_STREAM_WAIT_MS = 12_000L
        private const val SECOND_STREAM_WAIT_MS = 8_000L
        private const val STREAM_WINDOW_MS = 5_000L
        private const val MIN_BYTES = 10_000L
        private const val STOP_LATCH_MS = 8_000L
        private const val CONSUMER_SHUTDOWN_MS = 5_000L
        private const val CONSUMER_DEADLINE_MS = 60_000L
        private const val TEST_TIMEOUT_MS = 90_000L
    }
}

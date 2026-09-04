/*
 * SPDX-FileCopyrightText: 2026 Alfredo Medrano Sanchez <alfredomedranosanchez@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.camera

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.ThreadHelper.execute
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CameraSession(
    private val device: Device,
    private val request: Request,
    private val callbacks: Callbacks,
) {
    data class Request(
        val cameraId: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val rotationDegrees: Int = 0,
    )

    interface Callbacks {
        fun onSessionFailed(error: CameraError)
        fun onSessionStopped()
    }

    companion object {
        private const val TAG = "KDE/CameraSession"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val OPEN_TIMEOUT_SECONDS = 5L
        private const val SESSION_TIMEOUT_SECONDS = 5L
        private const val WATCHDOG_INTERVAL_MS = 2000L
    }

    private val handlerThread = HandlerThread("CameraSession").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var codec: MediaCodec? = null
    private var inputSurface: android.view.Surface? = null
    private var stream: StreamedPayloadInputStream? = null

    private val stopping = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private var csdBuffers: List<ByteBuffer>? = null

    // ── Watchdog state (accessed only from handler thread) ──────────────
    private val stallDetector = StallDetector()

    /** Last time an output buffer was produced. Initialised before codec.start(). */
    @Volatile
    private var lastOutputAtNanos = 0L

    /** Total frames written to the stream since codec start. */
    @Volatile
    private var framesOut = 0L

    /** Total bytes written to the stream since codec start. */
    @Volatile
    private var bytesOut = 0L

    /**
     * Timestamp (ns) of the last encoder keyframe request issued after a
     * backpressure drop. Rate-limited to 1/s: IDR frames are large, and an
     * unthrottled drop→IDR→drop loop would keep the stream permanently
     * stuck on keyframes (and inflate the bitrate).
     */
    @Volatile
    private var lastSyncRequestNanos = 0L

    /** Self-reposting watchdog runnable; stored so releaseAll() can cancel it. */
    private val watchdogRunnable: Runnable = Runnable {
        if (stopping.get()) return@Runnable

        val now = System.nanoTime()
        val s = stream
        val buffered = s?.bufferedBytes ?: 0
        val maxBuf = s?.maxBufferedBytes ?: 0
        val closed = s?.isClosed ?: false

        if (stallDetector.tick(now, lastOutputAtNanos, buffered, maxBuf, closed)) {
            Log.w(TAG, "Watchdog: stall detected — tearing down session")
            releaseAll()
            postFailure(CameraError.DISCONNECTED)
            return@Runnable
        }

        if (stallDetector.shouldLogStats()) {
            Log.i(TAG, "camera stream stats: frames=$framesOut bytes=$bytesOut buffered=$buffered")
        }

        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    /**
     * Camera2 callbacks must NOT be dispatched to [handler]: startInternal()
     * blocks that thread on latches that only those callbacks can count down
     * (self-deadlock otherwise). They run on the main Looper instead; all
     * cross-thread field access is guarded by [resourceLock].
     */
    private val cameraCallbackHandler = Handler(Looper.getMainLooper())

    /** Guards cameraDevice/captureSession swaps against late callbacks. */
    private val resourceLock = Any()

    // Latches of the currently-running start sequence, published so stop() can
    // release the handler thread when it is blocked awaiting camera open or
    // capture-session configuration (otherwise stop() deadlocks behind them).
    @Volatile
    private var openLatchRef: CountDownLatch? = null

    @Volatile
    private var sessionLatchRef: CountDownLatch? = null

    /**
     * Set when startInternal abandons the capture-session wait (timeout or
     * early stop). The CameraCaptureSession.StateCallback runs on our handler
     * (which is blocked while we wait), so it fires only AFTER the abandoned
     * wait returns — without this flag, a late onConfigured would store a
     * session that releaseAll() already missed, leaking the camera.
     */
    @Volatile
    private var sessionAbandoned = false

    fun start() {
        handler.post {
            if (stopping.get()) return@post // stop() raced us
            try {
                startInternal()
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected exception during start", e)
                sessionAbandoned = true
                releaseAll()
                postFailure(CameraError.UNSUPPORTED)
            }
        }
    }

    fun stop() {
        if (stopping.getAndSet(true)) return
        // Unblock the handler thread if it is waiting inside startInternal().
        openLatchRef?.countDown()
        sessionLatchRef?.countDown()
        handler.post {
            sessionAbandoned = true
            releaseAll()
            if (stopped.compareAndSet(false, true)) {
                callbacks.onSessionStopped()
            }
            handlerThread.quitSafely()
        }
    }

    private fun postFailure(error: CameraError) {
        // If stop() raced us (e.g. it released a latch we were awaiting), this
        // is an orderly shutdown, not a failure: report stopped, not failed.
        if (stopping.get()) {
            if (stopped.compareAndSet(false, true)) {
                callbacks.onSessionStopped()
            }
        } else if (stopped.compareAndSet(false, true)) {
            callbacks.onSessionFailed(error)
        }
        handlerThread.quitSafely()
    }

    @SuppressLint("MissingPermission")
    private fun startInternal() {
        val cameraManager = device.context.getSystemService(CameraManager::class.java)
            ?: throw IllegalStateException("CameraManager not available")

        val openLatch = CountDownLatch(1)
        openLatchRef = openLatch
        val openError = AtomicReference<CameraError?>(null)

        try {
            cameraManager.openCamera(
                request.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        // This callback may fire after an abandoned wait
                        // (quitSafely runs queued messages): never store a
                        // camera we would not release.
                        synchronized(resourceLock) {
                            if (stopping.get() || stopped.get() || cameraDevice != null) {
                                Log.w(TAG, "Late onOpened, closing camera immediately")
                                try {
                                    camera.close()
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error closing late camera", e)
                                }
                                openLatch.countDown()
                                return
                            }
                            cameraDevice = camera
                        }
                        openLatch.countDown()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(TAG, "CameraDevice.StateCallback.onError: $error")
                        try {
                            camera.close()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error closing camera after error", e)
                        }
                        openError.set(
                            when (error) {
                                CameraDevice.StateCallback.ERROR_CAMERA_IN_USE,
                                CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE,
                                -> CameraError.IN_USE
                                else -> CameraError.UNSUPPORTED
                            }
                        )
                        openLatch.countDown()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        Log.w(TAG, "Camera disconnected")
                        try {
                            camera.close()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error closing camera after disconnect", e)
                        }
                        val wasStreaming: Boolean
                        synchronized(resourceLock) {
                            wasStreaming = cameraDevice === camera && !stopping.get()
                            if (cameraDevice === camera) cameraDevice = null
                        }
                        if (wasStreaming) {
                            // Another app/hardware took the camera mid-stream:
                            // tear the session down as a failure.
                            handler.post {
                                releaseAll()
                                postFailure(CameraError.DISCONNECTED)
                            }
                        }
                        openError.set(CameraError.DISCONNECTED)
                        openLatch.countDown()
                    }
                },
                cameraCallbackHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "CameraAccessException opening camera", e)
            releaseAll()
            postFailure(CameraError.UNSUPPORTED)
            return
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException opening camera", e)
            releaseAll()
            postFailure(CameraError.DENIED)
            return
        }

        if (!openLatch.await(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Log.e(TAG, "Camera open timed out")
            sessionAbandoned = true
            releaseAll()
            postFailure(CameraError.UNSUPPORTED)
            return
        }
        openLatchRef = null

        if (openError.get() != null) {
            releaseAll()
            postFailure(openError.get()!!)
            return
        }

        val camera: CameraDevice
        synchronized(resourceLock) {
            camera = cameraDevice ?: run {
                releaseAll()
                postFailure(CameraError.UNSUPPORTED)
                return
            }
        }
        Log.i(TAG, "Camera ${request.cameraId} opened")

        val (effectiveWidth, effectiveHeight, effectiveFps) = resolveEffectiveParams(cameraManager)
        Log.i(TAG, "Effective params: ${effectiveWidth}x${effectiveHeight} @ ${effectiveFps}fps")

        try {
            configureEncoder(effectiveWidth, effectiveHeight, effectiveFps)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure encoder", e)
            releaseAll()
            postFailure(CameraError.UNSUPPORTED)
            return
        }

        val surface = inputSurface!!
        try {
            createCaptureSession(camera, surface)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session", e)
            releaseAll()
            postFailure(CameraError.UNSUPPORTED)
            return
        }

        val streamInstance = StreamedPayloadInputStream()
        stream = streamInstance

        val np = NetworkPacket(CameraProtocol.PACKET_TYPE_CAMERA_STREAM)
        np[CameraProtocol.KEY_CAMERA_ID] = request.cameraId
        val isRotated = request.rotationDegrees % 360 == 90 || request.rotationDegrees % 360 == 270
        if (isRotated) {
            np[CameraProtocol.KEY_WIDTH] = effectiveHeight
            np[CameraProtocol.KEY_HEIGHT] = effectiveWidth
        } else {
            np[CameraProtocol.KEY_WIDTH] = effectiveWidth
            np[CameraProtocol.KEY_HEIGHT] = effectiveHeight
        }
        np[CameraProtocol.KEY_FPS] = effectiveFps
        np[CameraProtocol.KEY_ROTATION] = request.rotationDegrees
        np.payload = NetworkPacket.Payload(streamInstance, CameraProtocol.UNKNOWN_PAYLOAD_SIZE)
        // Async sendPacket closes the payload in its finally; use same-thread
        // payload send on a background thread to keep the stream open.
        execute {
            device.sendPacketBlocking(np, object : Device.SendPacketStatusCallback() {
                override fun onSuccess() {
                    // Stream end is detected via finish()/cancel, not the callback
                }

                override fun onFailure(e: Throwable) {
                    Log.w(TAG, "Send stream packet failed", e)
                    if (!stopping.get() && !stopped.get()) {
                        handler.post {
                            releaseAll()
                            postFailure(CameraError.DISCONNECTED)
                        }
                    }
                }
            }, true)
        }

        try {
            lastOutputAtNanos = System.nanoTime()
            codec!!.start()
            Log.i(TAG, "Codec started, streaming active")
            handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start codec", e)
            releaseAll()
            postFailure(CameraError.UNSUPPORTED)
            return
        }
    }

    private fun resolveEffectiveParams(
        cameraManager: CameraManager
    ): Triple<Int, Int, Int> {
        val chars = cameraManager.getCameraCharacteristics(request.cameraId)
        val configMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        if (configMap == null) {
            Log.w(TAG, "No StreamConfigurationMap, using request values directly")
            return Triple(request.width, request.height, request.fps)
        }

        val outputSizes = configMap.getOutputSizes(SurfaceTexture::class.java)
        if (outputSizes.isNullOrEmpty()) {
            Log.w(TAG, "No output sizes available, using request values directly")
            return Triple(request.width, request.height, request.fps)
        }

        val requestPixels = request.width.toLong() * request.height.toLong()
        val bestSize = outputSizes.minByOrNull { size ->
            val pixels = size.width.toLong() * size.height.toLong()
            val diff = kotlin.math.abs(pixels - requestPixels)
            diff * 1_000_000_000L + pixels
        }!!

        val minDuration = configMap.getOutputMinFrameDuration(SurfaceTexture::class.java, bestSize)
        val maxFps = if (minDuration > 0) (1_000_000_000.0 / minDuration).toInt() else request.fps
        val effectiveFps = minOf(request.fps, maxFps).coerceAtLeast(1)

        return Triple(bestSize.width, bestSize.height, effectiveFps)
    }

    private fun configureEncoder(width: Int, height: Int, fps: Int) {
        val mediaCodec = MediaCodec.createEncoderByType(MIME)
        codec = mediaCodec

        // Candidate formats, most to least aggressive. Some vendor encoders
        // (Qualcomm OMX on SM6125 / Redmi Note 9S) accept unknown MediaFormat
        // keys but then fail configure() with EINVAL (0xffffffea), so we
        // cascade down to a plain baseline format on rejection.
        data class Variant(val lowLatency: Boolean, val profile: Boolean)
        val variants = listOf(Variant(true, true), Variant(true, false), Variant(false, false))

        // setCallback once: calling it a second time throws
        // "callback is already set!" on some devices. A failed configure()
        // leaves the codec Uninitialized and re-configurable on the same
        // instance, so the retry loop below does NOT re-set the callback.
        mediaCodec.setCallback(createCodecCallback(), handler)

        var lastError: Exception? = null
        var configured = false
        for (variant in variants) {
            try {
                mediaCodec.configure(
                    buildEncoderFormat(width, height, fps, variant.lowLatency, variant.profile),
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
                )
                configured = true
                if (!variant.lowLatency) Log.i(TAG, "Encoder configured without low-latency hints (device rejected them)")
                break
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Encoder configure failed (lowLatency=${variant.lowLatency}, profile=${variant.profile}), retrying with fewer hints", e)
            }
        }
        if (!configured) {
            Log.e(TAG, "Failed to configure encoder", lastError)
            throw lastError ?: IllegalStateException("Encoder configure failed")
        }

        inputSurface = mediaCodec.createInputSurface()
    }

    private fun buildEncoderFormat(width: Int, height: Int, fps: Int, withLowLatency: Boolean, withProfile: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(MIME, width, height).apply {
            // REQUIRED for surface-input encoders: the Qualcomm HAL on
            // SM6125 (and others) rejects configure() with CodecException
            // 0x80001001 without this key. Found on real hardware in the
            // CAM-7 spike.
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            @Suppress("DEPRECATION")
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_BIT_RATE, request.bitrate)
            setLong(MediaFormat.KEY_I_FRAME_INTERVAL, 1L)

            // Low-latency encode hints (this is a live webcam stream, not a
            // file): emit each frame with minimal internal buffering (API 30+)
            // and run the codec in realtime priority (API 31+). Optional keys:
            // configure() cascades without them on codecs that reject them.
            if (withLowLatency) {
                if (Build.VERSION.SDK_INT >= 30) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    // 0 = realtime priority
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
            }

            if (request.rotationDegrees % 360 != 0) {
                if (Build.VERSION.SDK_INT >= 24) {
                    setInteger(MediaFormat.KEY_ROTATION, request.rotationDegrees)
                } else {
                    Log.w(TAG, "KEY_ROTATION requires API 24+, current=${Build.VERSION.SDK_INT}, rotation ignored")
                }
            }

            if (withProfile && Build.VERSION.SDK_INT >= 34) {
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                )
                setInteger(
                    MediaFormat.KEY_LEVEL,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel31
                )
            }
        }

    private fun createCodecCallback(): MediaCodec.Callback {
        return object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                // Encoder with input surface: we don't feed input buffers
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                bufferInfo: MediaCodec.BufferInfo
            ) {
                try {
                    // Update timestamp before the stopping check: even if
                    // stop() just set the flag, the watchdog must see recent
                    // activity to avoid a false-positive stall during teardown.
                    lastOutputAtNanos = System.nanoTime()

                    if (stopping.get()) return

                    val buf = codec.getOutputBuffer(index) ?: return
                    val info = bufferInfo

                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        val format = codec.outputFormat
                        val csdList = mutableListOf<ByteBuffer>()
                        format.getByteBuffer("csd-0")?.let { sps ->
                            val copy = ByteBuffer.allocate(sps.remaining())
                            copy.put(sps)
                            copy.flip()
                            csdList.add(copy)
                        }
                        format.getByteBuffer("csd-1")?.let { pps ->
                            val copy = ByteBuffer.allocate(pps.remaining())
                            copy.put(pps)
                            copy.flip()
                            csdList.add(copy)
                        }
                        csdBuffers = csdList
                        return
                    }

                    val streamInstance = stream ?: return

                    if (streamInstance.isClosed) {
                        handler.post {
                            releaseAll()
                            postFailure(CameraError.DISCONNECTED)
                        }
                        return
                    }

                    val isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    val annexb = AnnexBUtils.toAnnexB(buf, csdBuffers, includeCsd = isKeyFrame)
                    val droppedChunks = streamInstance.write(annexb, 0, annexb.size)
                    if (droppedChunks > 0) {
                        // Stale frames were dropped to keep latency bounded.
                        // Ask the encoder for an immediate keyframe so the host
                        // can resynchronise at once instead of waiting for the
                        // next IDR interval (1 s) — otherwise every drop costs
                        // up to a second of frozen/black picture. Rate-limited
                        // to 1/s (IDR frames are large; see field docs).
                        val now = System.nanoTime()
                        if (now - lastSyncRequestNanos > 1_000_000_000L) {
                            lastSyncRequestNanos = now
                            try {
                                codec.setParameters(
                                    android.os.Bundle().apply {
                                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                                    }
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "keyframe request after backpressure drop failed", e)
                            }
                        }
                    }
                    framesOut++
                    bytesOut += annexb.size
                    stallDetector.resetBackpressure()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onOutputBufferAvailable", e)
                    handler.post {
                        releaseAll()
                        postFailure(CameraError.UNSUPPORTED)
                    }
                } finally {
                    try {
                        codec.releaseOutputBuffer(index, false)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error releasing output buffer", e)
                    }
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "MediaCodec error", e)
                handler.post {
                    releaseAll()
                    postFailure(CameraError.UNSUPPORTED)
                }
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.i(TAG, "Output format changed: $format")
            }
        }
    }

    private fun createCaptureSession(camera: CameraDevice, surface: android.view.Surface) {
        val sessionLatch = CountDownLatch(1)
        sessionLatchRef = sessionLatch
        val sessionError = AtomicReference<CameraError?>(null)

        camera.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    // Late-callback guard: if the waiter already abandoned (or
                    // stop() ran), close the fresh session instead of storing it.
                    if (sessionAbandoned || stopping.get()) {
                        Log.w(TAG, "Late onConfigured, closing session immediately")
                        try {
                            session.close()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error closing late session", e)
                        }
                        sessionLatch.countDown()
                        return
                    }
                    synchronized(resourceLock) { captureSession = session }

                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    builder.addTarget(surface)

                    val chars = try {
                        device.context.getSystemService(CameraManager::class.java)
                            ?.getCameraCharacteristics(request.cameraId)
                    } catch (e: Exception) {
                        null
                    }
                    if (chars != null) {
                        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                        if (afModes != null &&
                            CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes
                        ) {
                            builder.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                        }
                    }
                    builder.set(
                        CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON
                    )

                    try {
                        session.setRepeatingRequest(builder.build(), null, handler)
                    } catch (e: CameraAccessException) {
                        Log.e(TAG, "Failed to set repeating request", e)
                        sessionError.set(CameraError.UNSUPPORTED)
                    }
                    sessionLatch.countDown()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Capture session configure failed")
                    sessionError.set(CameraError.UNSUPPORTED)
                    sessionLatch.countDown()
                }
            },
            cameraCallbackHandler
        )

        if (!sessionLatch.await(SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            sessionAbandoned = true
            throw IllegalStateException("Capture session creation timed out")
        }
        sessionLatchRef = null

        if (sessionError.get() != null) {
            sessionAbandoned = true
            throw IllegalStateException("Capture session error: ${sessionError.get()}")
        }
    }

    private fun releaseAll() {
        handler.removeCallbacks(watchdogRunnable)
        synchronized(resourceLock) {
        // 1. Codec: signal end of stream, stop, release
        codec?.let { c ->
            try {
                c.signalEndOfInputStream()
            } catch (e: Exception) {
                Log.w(TAG, "Error signaling end of input stream", e)
            }
            try {
                c.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Error stopping codec (may not have been started)", e)
            } catch (e: MediaCodec.CodecException) {
                Log.w(TAG, "CodecException stopping codec", e)
            }
            try {
                c.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing codec", e)
            }
        }
        codec = null

        // 2. Input surface
        inputSurface?.let {
            try {
                it.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing input surface", e)
            }
        }
        inputSurface = null

        // 3. Capture session
        captureSession?.let {
            try {
                it.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing capture session", e)
            }
        }
        captureSession = null

        // 4. Camera device
        cameraDevice?.let {
            try {
                it.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing camera device", e)
            }
        }
        cameraDevice = null

        // 5. Stream: finish so the LanLink pump sees -1 and closes the socket
        stream?.let {
            try {
                it.finish()
            } catch (e: Exception) {
                Log.w(TAG, "Error finishing stream", e)
            }
        }
        stream = null
        }
    }
}

/**
 * Pure-JVM stall detector for camera streaming. Stateless about time sources
 * — the caller provides [nowNanos] on every tick.
 *
 * Detection rules:
 * 1. **No output stall**: no output buffer for [stallNanos] while codec is
 *    started and session is not stopping.
 * 2. **Backpressure stall**: stream buffered bytes >= max for
 *    [backpressureThreshold] consecutive ticks.
 * 3. **Stats cadence**: [shouldLogStats] returns true every [statsIntervalTicks] ticks.
 *
 * Thread safety: all mutable state is accessed only from the caller (handler
 * thread). This class is intentionally NOT thread-safe — it is a pure
 * value-accumulator driven by a single thread.
 *
 * @param stallNanos Nanoseconds without output before declaring a stall.
 * @param backpressureThreshold Consecutive full-buffer ticks before stall.
 * @param statsIntervalTicks Log stats every N ticks.
 */
internal class StallDetector(
    private val stallNanos: Long = 10_000_000_000L,
    private val backpressureThreshold: Int = 5,
    private val statsIntervalTicks: Int = 5,
) {
    private var consecutiveBackpressureTicks = 0
    private var tickCount = 0

    /**
     * Evaluate one watchdog tick.
     *
     * @param nowNanos Current monotonic time (e.g. [System.nanoTime]).
     * @param lastOutputNanos Timestamp of the most recent output buffer.
     *   Must be initialised before codec.start() so the startup budget is
     *   included in the stall window.
     * @param buffered Current bytes buffered in the stream, or 0 if no stream.
     * @param maxBuffered Maximum bytes before back-pressure, or 0 if no stream.
     * @param streamClosed Whether the stream consumer has closed.
     * @return true if the session should be torn down (stall detected).
     */
    fun tick(
        nowNanos: Long,
        lastOutputNanos: Long,
        buffered: Int,
        maxBuffered: Int,
        streamClosed: Boolean,
    ): Boolean {
        tickCount++

        // Rule 1: no output for too long
        if (nowNanos - lastOutputNanos > stallNanos) {
            return true
        }

        // Rule 2: backpressure — only when stream is open and at capacity
        if (!streamClosed && maxBuffered > 0 && buffered >= maxBuffered) {
            consecutiveBackpressureTicks++
            if (consecutiveBackpressureTicks >= backpressureThreshold) {
                return true
            }
        } else {
            consecutiveBackpressureTicks = 0
        }

        return false
    }

    /**
     * Whether this tick should emit a stats log line. Returns true exactly
     * every [statsIntervalTicks] ticks (i.e. tick 5, 10, 15, …).
     */
    fun shouldLogStats(): Boolean = tickCount % statsIntervalTicks == 0

    /**
     * Reset backpressure counter. Called when a new frame arrives so that
     * a burst of output clears the consecutive-full-buffer streak.
     */
    fun resetBackpressure() {
        consecutiveBackpressureTicks = 0
    }
}

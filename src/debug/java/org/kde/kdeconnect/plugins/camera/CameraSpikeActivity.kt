/*
 * SPDX-FileCopyrightText: 2026 Alfredo Medrano Sanchez <alfredomedranosanchez@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.core.app.ActivityCompat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Debug-only feasibility spike: Camera2 → MediaCodec H.264 → file.
 *
 * Validates the production encoder configuration (mirrored from [CameraSession])
 * on real hardware without any KDE Connect transport involved.
 *
 * The output file (`spike.264`) can be pulled via `adb pull` and played with
 * ffplay/mpv to verify correct Annex-B output.
 */
class CameraSpikeActivity : Activity() {

    companion object {
        private const val TAG = "KDE/CameraSpike"
        private const val REQUEST_CAMERA = 1001
        private const val CAPTURE_DURATION_MS = 10_000L
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val TARGET_W = 1280
        private const val TARGET_H = 720
        private const val TARGET_FPS = 30
        private const val BITRATE = 4_000_000
        private const val OPEN_TIMEOUT_S = 5L
        private const val SESSION_TIMEOUT_S = 5L
    }

    private val ht = HandlerThread("CameraSpike").apply { start() }
    private val handler = Handler(ht.looper)

    // Camera2 callbacks must NOT run on [handler]: startCapture() blocks that
    // thread on latches only those callbacks can count down (self-deadlock,
    // same guard as production CameraSession).
    private val cameraCallbackHandler = Handler(Looper.getMainLooper())

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var csdBuffers: List<ByteBuffer>? = null
    private var out: BufferedOutputStream? = null

    @Volatile private var running = false
    @Volatile private var frames = 0L
    @Volatile private var bytes = 0L
    @Volatile private var t0 = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            begin()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
        if (rc == REQUEST_CAMERA && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) begin()
        else { Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show(); finish() }
    }

    private fun begin() {
        handler.post {
            try { startCapture() }
            catch (e: Exception) {
                Log.e(TAG, "Spike failed", e)
                runOnUiThread { Toast.makeText(this, "Spike failed: ${e.message}", Toast.LENGTH_LONG).show(); finish() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCapture() {
        val catalog = Camera2Catalog(this)
        val cameras = catalog.listCameras()
        if (cameras.isEmpty()) throw IllegalStateException("No cameras found")
        val cam = cameras.first()
        val size = pickSize(cam.sizes)
        Log.i(TAG, "Camera ${cam.id} → ${size.width}x${size.height}@${size.fps}")

        // Open camera
        val mgr = getSystemService(CameraManager::class.java)!!
        val latch = CountDownLatch(1)
        var err: Exception? = null
        mgr.openCamera(cam.id, object : CameraDevice.StateCallback() {
            override fun onOpened(c: CameraDevice) { camera = c; latch.countDown() }
            override fun onError(c: CameraDevice, e: Int) { err = IllegalStateException("cam err $e"); c.close(); latch.countDown() }
            override fun onDisconnected(c: CameraDevice) { err = IllegalStateException("disconnected"); c.close(); latch.countDown() }
        }, cameraCallbackHandler)
        if (!latch.await(OPEN_TIMEOUT_S, TimeUnit.SECONDS)) throw IllegalStateException("open timeout")
        err?.let { throw it }

        // Encoder — mirrors CameraSession.configureEncoder exactly
        configureEncoder(size.width, size.height, size.fps)

        // Capture session
        val sLatch = CountDownLatch(1)
        var sErr: Exception? = null
        val surface = inputSurface!!
        camera!!.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val b = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(surface)
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                try { s.setRepeatingRequest(b.build(), null, handler) } catch (e: Exception) { sErr = e }
                sLatch.countDown()
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { sErr = IllegalStateException("session failed"); sLatch.countDown() }
        }, cameraCallbackHandler)
        if (!sLatch.await(SESSION_TIMEOUT_S, TimeUnit.SECONDS)) throw IllegalStateException("session timeout")
        sErr?.let { throw it }

        out = BufferedOutputStream(FileOutputStream(File(getExternalFilesDir(null), "spike.264")))
        running = true
        t0 = System.nanoTime()
        codec!!.start()
        Log.i(TAG, "Recording ${CAPTURE_DURATION_MS}ms")
        handler.postDelayed({ stop() }, CAPTURE_DURATION_MS)
    }

    /** Encoder config mirrored from CameraSession: VBR, I-frame 1s, baseline profile (SDK≥34), surface input. */
    private fun configureEncoder(w: Int, h: Int, fps: Int) {
        val mc = MediaCodec.createEncoderByType(MIME)
        codec = mc

        fun buildFormat(withProfile: Boolean): MediaFormat =
            MediaFormat.createVideoFormat(MIME, w, h).apply {
                // REQUIRED for surface-input encoders (Qualcomm HAL rejects
                // configure() with 0x80001001 without it).
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                @Suppress("DEPRECATION")
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setLong(MediaFormat.KEY_I_FRAME_INTERVAL, 1L)
                if (withProfile && Build.VERSION.SDK_INT >= 34) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                }
            }

        // setCallback once; re-setting on retry throws on some devices.
        mc.setCallback(codecCallback(), handler)
        try {
            mc.configure(buildFormat(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.w(TAG, "Encoder configure failed, retrying without profile/level", e)
            mc.configure(buildFormat(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = mc.createInputSurface()
    }

    private fun codecCallback() = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (!running) return
            try {
                val buf = codec.getOutputBuffer(index) ?: return
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    // Capture CSD (SPS/PPS) — same pattern as CameraSession
                    val fmt = codec.outputFormat
                    csdBuffers = listOfNotNull(fmt.getByteBuffer("csd-0"), fmt.getByteBuffer("csd-1")).map { orig ->
                        ByteBuffer.allocate(orig.remaining()).also { it.put(orig); it.flip() }
                    }
                    return
                }
                val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val annexb = AnnexBUtils.toAnnexB(buf, csdBuffers, includeCsd = isKey)
                synchronized(this@CameraSpikeActivity) { out?.write(annexb) }
                frames++; bytes += annexb.size
                if (frames % TARGET_FPS == 0L) {
                    val sec = (System.nanoTime() - t0) / 1_000_000_000L
                    Log.i(TAG, "t=${sec}s frames=$frames avgKbps=${if (sec > 0) bytes * 8 / sec / 1000 else 0}")
                }
            } catch (e: Exception) { Log.e(TAG, "output error", e) }
            finally { try { codec.releaseOutputBuffer(index, false) } catch (_: Exception) {} }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) { Log.e(TAG, "codec error", e) }
        override fun onOutputFormatChanged(codec: MediaCodec, fmt: MediaFormat) { Log.i(TAG, "format changed: $fmt") }
    }

    private fun pickSize(sizes: List<CameraSize>): CameraSize {
        if (sizes.isEmpty()) return CameraSize(TARGET_W, TARGET_H, TARGET_FPS)
        val target = TARGET_W.toLong() * TARGET_H.toLong()
        return sizes.minByOrNull { s ->
            val px = s.width.toLong() * s.height.toLong()
            kotlin.math.abs(px - target) * 1_000_000_000L + kotlin.math.abs(s.fps - TARGET_FPS)
        }!!
    }

    private fun stop() {
        if (!running) return
        running = false
        val sec = (System.nanoTime() - t0) / 1_000_000_000L
        val kbps = if (sec > 0) bytes * 8 / sec / 1000 else 0
        Log.i(TAG, "t=${sec}s frames=$frames avgKbps=$kbps")
        releaseAll()
        val f = File(getExternalFilesDir(null), "spike.264")
        val msg = "spike.264 written: ${f.length()} bytes, ~${kbps} kbps, $frames frames"
        Log.i(TAG, msg)
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); finish() }
    }

    private fun releaseAll() {
        try { codec?.signalEndOfInputStream() } catch (_: Exception) {}
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        synchronized(this) {
            try { out?.flush() } catch (_: Exception) {}
            try { out?.close() } catch (_: Exception) {}
            out = null
        }
    }

    override fun onDestroy() {
        running = false
        releaseAll()
        ht.quitSafely()
        super.onDestroy()
    }
}

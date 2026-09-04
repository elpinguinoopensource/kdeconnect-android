/*
 * SPDX-FileCopyrightText: 2026 Alfredo Medrano Sanchez <alfredomedranosanchez@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R

/**
 * Plugin that exposes the device's cameras as a webcam to the connected desktop.
 *
 * Handles `kdeconnect.camera.list` requests by returning the camera catalog,
 * and handles `kdeconnect.camera.start` / `kdeconnect.camera.stop`
 * to stream H.264 video via `kdeconnect.camera.stream`.
 */
@LoadablePlugin
class CameraPlugin : Plugin() {

    /**
     * Factory for obtaining a [CameraCatalog]. Defaults to [Camera2Catalog]
     * which queries the Camera2 API. Tests can inject a fake catalog via this property.
     *
     * [UnavailableCameraCatalog] is the fallback used by tests and when Camera2
     * is unavailable.
     */
    internal var catalogProvider: (Context) -> CameraCatalog = { Camera2Catalog(it) }

    /**
     * Factory for creating a [CameraSession]. Tests can inject a fake session factory.
     */
    internal var sessionFactory: (Device, CameraSession.Request, CameraSession.Callbacks) -> CameraSession = { d, r, c -> CameraSession(d, r, c) }

    /**
     * Seam for sending foreground service promote/demote intents.
     * Tests can override this to record calls without starting the real service.
     */
    internal var sendCameraServiceIntent: (active: Boolean) -> Unit = { active ->
        try {
            val i = Intent(context, BackgroundService::class.java)
                .putExtra("cameraActive", active)
                .putExtra("cameraDeviceName", device.name)
                .putExtra("deviceId", device.deviceId)
            ContextCompat.startForegroundService(context, i)
        } catch (e: Exception) {
            Log.w(TAG, "Could not promote/demote foreground service: $e")
        }
    }

    private val lock = Any()
    private var session: CameraSession? = null

    /**
     * Held for the whole lifetime of an active camera session. With the screen
     * off, this device (Redmi Note 9S, MIUI) parks the CPU and puts the WiFi
     * radio into power-save: RTT goes from ~3ms to 100-200ms and payload
     * throughput collapses (~80kbps at 1080p), which makes the webcam stream
     * laggy. A non-reference-counted partial wake lock keeps the CPU (and the
     * WiFi stack) responsive while sharing; the 10-minute timeout is a safety
     * net in case a release path is ever missed.
     */
    private val wakeLock: PowerManager.WakeLock by lazy {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kdeconnect:camera-share").apply {
            setReferenceCounted(false)
        }
    }

    /**
     * Seam for holding a wake lock for the lifetime of a camera session.
     * Tests can override this to record calls without touching PowerManager.
     */
    internal var wakeLockController: (Boolean) -> Unit = { active ->
        try {
            if (active) {
                if (!wakeLock.isHeld) wakeLock.acquire(10 * 60 * 1000L)
            } else {
                if (wakeLock.isHeld) wakeLock.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not toggle camera wake lock (active=$active): $e")
        }
    }
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    internal var mainHandler: Handler = Handler(Looper.getMainLooper())

    override val displayName: String
        get() = context.getString(R.string.cameraplugin_title)

    override val description: String
        get() = context.getString(R.string.cameraplugin_description)

    override val minSdk: Int = Build.VERSION_CODES.LOLLIPOP

    override val requiredPermissions: Array<String>
        get() = arrayOf(Manifest.permission.CAMERA)

    override val supportedPacketTypes: Array<String>
        get() = arrayOf(
            CameraProtocol.PACKET_TYPE_CAMERA_LIST,
            CameraProtocol.PACKET_TYPE_CAMERA_START,
            CameraProtocol.PACKET_TYPE_CAMERA_STOP,
        )

    override val outgoingPacketTypes: Array<String>
        get() = arrayOf(
            CameraProtocol.PACKET_TYPE_CAMERA_LIST,
            CameraProtocol.PACKET_TYPE_CAMERA_STREAM,
            CameraProtocol.PACKET_TYPE_CAMERA_ERROR,
        )

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        return when (np.type) {
            CameraProtocol.PACKET_TYPE_CAMERA_LIST -> {
                sendCameraList()
                true
            }
            CameraProtocol.PACKET_TYPE_CAMERA_START -> {
                handleCameraStart(np)
                true
            }
            CameraProtocol.PACKET_TYPE_CAMERA_STOP -> {
                handleCameraStop()
                true
            }
            else -> false
        }
    }

    /**
     * Handle a `camera.start` request from the desktop.
     */
    private fun handleCameraStart(np: NetworkPacket) {
        // Check camera permission
        if (!isPermissionGranted(Manifest.permission.CAMERA)) {
            sendCameraError(CameraError.DENIED)
            return
        }

        // Parse and clamp the request
        val catalog = catalogProvider(context).listCameras()
        val req = clampRequest(np, catalog)
        if (req == null) {
            sendCameraError(CameraError.UNSUPPORTED)
            return
        }

        startSession(req, sendErrorOnBusy = true)
    }

    /**
     * Shared session-start logic used by both desktop-initiated
     * ([handleCameraStart]) and phone-initiated ([startSharing]) paths.
     *
     * @param sendErrorOnBusy if true, sends an [CameraError.IN_USE] error
     *   packet when a session is already active (desktop-initiated path).
     *   The phone-initiated path sets this to false and returns false instead.
     * @return true if a session was started (or queued); false if busy.
     */
    private fun startSession(req: CameraSession.Request, sendErrorOnBusy: Boolean): Boolean {
        // Guard: session already active
        synchronized(lock) {
            if (session != null) {
                if (sendErrorOnBusy) {
                    sendCameraError(CameraError.IN_USE)
                }
                return false
            }
        }

        // Start session on a background thread
        ThreadHelper.execute {
            val callbacks = object : CameraSession.Callbacks {
                override fun onSessionFailed(error: CameraError) {
                    sendCameraError(error)
                    synchronized(lock) { session = null }
                    wakeLockController(false)
                    sendCameraServiceIntent(false)
                    notifyActiveListeners(false)
                }

                override fun onSessionStopped() {
                    synchronized(lock) { session = null }
                    wakeLockController(false)
                    sendCameraServiceIntent(false)
                    notifyActiveListeners(false)
                }
            }

            val s = sessionFactory(device, req, callbacks)
            synchronized(lock) {
                if (session != null) {
                    // Another session started while we were creating this one
                    s.stop()
                    return@execute
                }
                session = s
            }
            wakeLockController(true)
            sendCameraServiceIntent(true)
            notifyActiveListeners(true)
            s.start()
        }
        return true
    }

    /**
     * Handle a `camera.stop` request from the desktop.
     */
    private fun handleCameraStop() {
        stopSession(userInitiated = false)
    }

    /**
     * Stop the current session. Thread-safe.
     * @param userInitiated if true, sends a [CameraError.STOPPED] error packet to the host.
     */
    fun stopSession(userInitiated: Boolean) {
        val s = synchronized(lock) {
            val tmp = session
            session = null
            tmp
        }
        s?.stop()
        wakeLockController(false)
        sendCameraServiceIntent(false)
        if (userInitiated) {
            sendCameraError(CameraError.STOPPED)
        }
        notifyActiveListeners(false)
    }

    /**
     * Start sharing the camera from the phone UI (local initiation).
     * Does NOT send an error packet on failure — nothing is connected as requester.
     * @return true if a session was accepted; false if permission missing or busy.
     */
    fun startSharing(request: CameraSession.Request): Boolean {
        if (!isPermissionGranted(Manifest.permission.CAMERA)) {
            return false
        }
        return startSession(request, sendErrorOnBusy = false)
    }

    /**
     * Stop a locally-initiated sharing session. Equivalent to
     * `stopSession(userInitiated = true)`.
     */
    fun stopSharing() {
        stopSession(userInitiated = true)
    }

    /**
     * Whether a camera session is currently active.
     */
    fun isSharing(): Boolean = synchronized(lock) { session != null }

    /**
     * Register a listener that is notified on the main thread whenever the
     * sharing state changes. The callback receives `true` when a session
     * starts and `false` when it stops.
     */
    fun addActiveListener(l: (Boolean) -> Unit) {
        listeners.add(l)
    }

    /**
     * Remove a previously registered active-state listener.
     */
    fun removeActiveListener(l: (Boolean) -> Unit) {
        listeners.remove(l)
    }

    /**
     * Notify all registered listeners on the main thread.
     */
    private fun notifyActiveListeners(active: Boolean) {
        mainHandler.post {
            for (l in listeners) {
                l(active)
            }
        }
    }

    /**
     * Builds and sends a `kdeconnect.camera.error` packet.
     */
    private fun sendCameraError(error: CameraError) {
        val np = NetworkPacket(CameraProtocol.PACKET_TYPE_CAMERA_ERROR)
        np[CameraProtocol.KEY_ERROR] = error.wireValue
        device.sendPacket(np)
    }

    /**
     * Builds and sends a `kdeconnect.camera.list` reply packet containing the
     * cameras reported by the current [catalogProvider].
     */
    internal fun sendCameraList() {
        try {
            val catalog = catalogProvider(context)
            val cameras = catalog.listCameras()

            val camerasArray = JSONArray()
            for (cam in cameras) {
                val camObj = JSONObject()
                camObj.put(CameraProtocol.KEY_CAMERA_ID, cam.id)
                camObj.put(CameraProtocol.KEY_FACING, cam.facing)
                camObj.put(CameraProtocol.KEY_HAS_FLASH, cam.hasFlash)

                val sizesArray = JSONArray()
                for (size in cam.sizes) {
                    val sizeObj = JSONObject()
                    sizeObj.put(CameraProtocol.KEY_WIDTH, size.width)
                    sizeObj.put(CameraProtocol.KEY_HEIGHT, size.height)
                    sizeObj.put(CameraProtocol.KEY_FPS, size.fps)
                    sizesArray.put(sizeObj)
                }
                camObj.put(CameraProtocol.KEY_SIZES, sizesArray)
                camerasArray.put(camObj)
            }

            val reply = NetworkPacket(CameraProtocol.PACKET_TYPE_CAMERA_LIST)
            reply[CameraProtocol.KEY_CAMERAS] = camerasArray
            device.sendPacket(reply)
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to build camera list packet", e)
        }
    }

    override fun getUiButtons(): List<PluginUiButton> = listOf(
        PluginUiButton(
            context.getString(R.string.cameraplugin_share_button),
            R.drawable.ic_presenter_24dp
        ) { parentActivity ->
            val intent = Intent(parentActivity, CameraShareActivity::class.java)
            intent.putExtra("deviceId", device.deviceId)
            parentActivity.startActivity(intent)
        }
    )

    override fun onDestroy() {
        val s = synchronized(lock) {
            val tmp = session
            session = null
            tmp
        }
        s?.stop()
        wakeLockController(false)
        sendCameraServiceIntent(false)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KDE/CameraPlugin"

        /**
         * Parse and clamp a camera.start request into a [CameraSession.Request].
         * Returns null if the catalog is empty or the requested cameraId is not found.
         */
        internal fun clampRequest(np: NetworkPacket, catalog: List<CameraDescription>): CameraSession.Request? {
            if (catalog.isEmpty()) {
                Log.w(TAG, "Camera catalog is empty")
                return null
            }

            // Resolve cameraId: use requested, or fall back to back camera, or first camera
            var cameraId = np.getString(CameraProtocol.KEY_CAMERA_ID)
            if (cameraId.isEmpty()) {
                cameraId = catalog.firstOrNull { it.facing == CameraProtocol.FACING_BACK }?.id
                    ?: catalog.first().id
                Log.i(TAG, "No cameraId specified, using fallback: $cameraId")
            }

            // Verify cameraId exists in catalog
            if (catalog.none { it.id == cameraId }) {
                Log.w(TAG, "Camera $cameraId not found in catalog")
                return null
            }

            // Parse and clamp dimensions
            var width = np.getInt(CameraProtocol.KEY_WIDTH, 1280)
            var height = np.getInt(CameraProtocol.KEY_HEIGHT, 720)
            var fps = np.getInt(CameraProtocol.KEY_FPS, 30)
            var bitrate = np.getInt(CameraProtocol.KEY_BITRATE, 4_000_000)

            // Clamp values
            val clampedWidth = width.coerceIn(160, 3840)
            if (clampedWidth != width) {
                Log.w(TAG, "Width clamped from $width to $clampedWidth")
                width = clampedWidth
            }

            val clampedHeight = height.coerceIn(160, 3840)
            if (clampedHeight != height) {
                Log.w(TAG, "Height clamped from $height to $clampedHeight")
                height = clampedHeight
            }

            val clampedFps = fps.coerceIn(1, 60)
            if (clampedFps != fps) {
                Log.w(TAG, "FPS clamped from $fps to $clampedFps")
                fps = clampedFps
            }

            val clampedBitrate = bitrate.coerceIn(100_000, 16_000_000)
            if (clampedBitrate != bitrate) {
                Log.w(TAG, "Bitrate clamped from $bitrate to $clampedBitrate")
                bitrate = clampedBitrate
            }

            return CameraSession.Request(
                cameraId = cameraId,
                width = width,
                height = height,
                fps = fps,
                bitrate = bitrate,
            )
        }
    }
}

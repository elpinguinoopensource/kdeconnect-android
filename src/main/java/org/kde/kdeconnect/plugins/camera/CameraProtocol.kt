/*
 * SPDX-FileCopyrightText: 2026 Alfredo Medrano Sanchez <alfredomedranosanchez@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.camera

/**
 * Protocol constants for the camera-as-webcam feature.
 *
 * **Design note:** The original plan placed the H.264 payload on `camera.start`,
 * but in KDE Connect `LanLink.sendPacket()` only opens the payload `ServerSocket`
 * on the *sender* of the packet. Since the stream flows from Android → Desktop,
 * the payload must ride on an android→desktop packet. Therefore:
 * - `camera.start` is a plain command (desktop → android, no payload)
 * - `camera.stream` carries the H.264 payload (android → desktop)
 */
object CameraProtocol {
    // Packet types
    const val PACKET_TYPE_CAMERA_LIST = "kdeconnect.camera.list"     // desktop -> android (request), android -> desktop (reply)
    const val PACKET_TYPE_CAMERA_START = "kdeconnect.camera.start"   // desktop -> android (command, no payload)
    const val PACKET_TYPE_CAMERA_STREAM = "kdeconnect.camera.stream" // android -> desktop (carries the H.264 payload)
    const val PACKET_TYPE_CAMERA_STOP = "kdeconnect.camera.stop"     // desktop -> android
    const val PACKET_TYPE_CAMERA_ERROR = "kdeconnect.camera.error"   // android -> desktop

    // Body keys
    const val KEY_CAMERAS = "cameras"
    const val KEY_CAMERA_ID = "cameraId"
    const val KEY_FACING = "facing"
    const val KEY_HAS_FLASH = "hasFlash"
    const val KEY_SIZES = "sizes"
    const val KEY_WIDTH = "width"
    const val KEY_HEIGHT = "height"
    const val KEY_FPS = "fps"
    const val KEY_BITRATE = "bitrate"
    const val KEY_ROTATION = "rotation"
    const val KEY_AUDIO = "audio"
    const val KEY_ERROR = "error"

    // facing values
    const val FACING_FRONT = "front"
    const val FACING_BACK = "back"
    const val FACING_EXTERNAL = "external"

    // error values (see CameraError)
    const val ERROR_IN_USE = "in_use"
    const val ERROR_DENIED = "denied"
    const val ERROR_UNSUPPORTED = "unsupported"
    const val ERROR_DISCONNECTED = "disconnected"
    const val ERROR_STOPPED = "stopped"

    /** Sentinel for NetworkPacket.Payload.payloadSize on an open-ended stream. */
    const val UNKNOWN_PAYLOAD_SIZE = -1L
}

/**
 * Typed error codes for camera operations, preventing callers from passing
 * arbitrary error strings over the wire.
 */
enum class CameraError(val wireValue: String) {
    IN_USE(CameraProtocol.ERROR_IN_USE),
    DENIED(CameraProtocol.ERROR_DENIED),
    UNSUPPORTED(CameraProtocol.ERROR_UNSUPPORTED),
    DISCONNECTED(CameraProtocol.ERROR_DISCONNECTED),
    STOPPED(CameraProtocol.ERROR_STOPPED),
}

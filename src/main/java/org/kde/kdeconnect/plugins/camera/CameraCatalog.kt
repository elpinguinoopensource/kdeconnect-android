/*
 * SPDX-FileCopyrightText: 2026 Alfredo Medrano Sanchez <alfredomedranosanchez@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.camera

/**
 * Describes a single supported resolution/fps combination for a camera.
 */
data class CameraSize(val width: Int, val height: Int, val fps: Int)

/**
 * Describes a single camera device and its capabilities.
 *
 * @property id    Platform-specific camera identifier (e.g. "0" for the first camera).
 * @property facing One of [CameraProtocol.FACING_FRONT], [CameraProtocol.FACING_BACK],
 *                  or [CameraProtocol.FACING_EXTERNAL].
 * @property hasFlash Whether the camera has a flash unit.
 * @property sizes Supported resolution/fps combinations.
 */
data class CameraDescription(
    val id: String,
    val facing: String,
    val hasFlash: Boolean,
    val sizes: List<CameraSize>,
)

/**
 * Abstraction over the platform camera enumeration API.
 *
 * This interface exists so that [CameraPlugin] can be tested without touching
 * Camera2 APIs. CAM-2 will provide the real implementation (`Camera2Catalog`)
 * that uses `CameraManager.cameraIdList` and `StreamConfigurationMap`.
 */
interface CameraCatalog {
    fun listCameras(): List<CameraDescription>
}

/**
 * Placeholder catalog that returns an empty list. Used as a fallback when
 * Camera2 is unavailable and by tests that do not need a real camera
 * enumeration. The production default is [Camera2Catalog].
 */
class UnavailableCameraCatalog : CameraCatalog {
    override fun listCameras(): List<CameraDescription> {
        android.util.Log.w("CameraCatalog", "Camera catalog not yet available (CAM-2 implements Camera2Catalog)")
        return emptyList()
    }
}

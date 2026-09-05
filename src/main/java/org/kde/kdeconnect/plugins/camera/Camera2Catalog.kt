/*
 * SPDX-FileCopyrightText: 2026 Alfredo Medrano Sanchez <alfredomedranosanchez@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.camera

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Pure mapping helper that filters and sanitizes a list of [CameraSize] entries.
 *
 * The goal is to keep the `camera.list` packet small and only expose sizes that
 * make sense as a webcam source.
 */
internal object CameraSizeFilter {

    private const val TOLERANCE = 0.02
    private val ASPECT_4_3 = 4.0 / 3.0
    private val ASPECT_16_9 = 16.0 / 9.0

    /**
     * Filters [sizes] to only include webcam-suitable resolutions.
     *
     * Rules applied in order:
     * 1. **Drop invalid dimensions:** width <= 0 or height <= 0 are discarded.
     * 2. **Drop oversized resolutions:** any size whose total pixel count exceeds
     *    [maxPixels] (default 1920×1080 = 2 073 600) is discarded.
     * 3. **Keep only standard aspect ratios:** only 4:3 and 16:9 are kept,
     *    with a ±2 % tolerance to account for rounding in manufacturer reports.
     * 4. **Dedupe by resolution:** when multiple fps entries share the same
     *    (width × height), only the one with the highest fps is kept.
     * 5. **Sort descending:** by total pixel count first, then by fps as
     *    tiebreaker.
     * 6. **Truncate:** at most [maxEntries] entries are returned.
     *
     * @param sizes raw sizes from the camera's [StreamConfigurationMap].
     * @param maxPixels maximum total pixel count (width × height) to keep.
     * @param maxEntries maximum number of entries in the returned list.
     * @return sanitized, sorted, deduplicated list of [CameraSize].
     */
    fun sanitize(
        sizes: List<CameraSize>,
        maxPixels: Int = 1920 * 1080,
        maxEntries: Int = 8,
    ): List<CameraSize> {
        // Step 1 & 2 & 3: filter invalid, oversized, and non-standard aspect ratios
        val filtered = sizes.filter { size ->
            size.width > 0 && size.height > 0 &&
                size.width.toLong() * size.height.toLong() <= maxPixels &&
                isStandardAspect(size.width, size.height)
        }

        // Step 4: dedupe by (width, height), keeping the highest fps
        val deduped = filtered
            .groupBy { it.width to it.height }
            .map { (_, group) -> group.maxByOrNull { it.fps }!! }

        // Step 5: sort descending by pixel count, then fps
        val sorted = deduped.sortedWith(
            compareByDescending<CameraSize> { it.width.toLong() * it.height.toLong() }
                .thenByDescending { it.fps }
        )

        // Step 6: truncate
        return sorted.take(maxEntries)
    }

    /**
     * Returns `true` if [width]/[height] is within ±2 % of 4:3 or 16:9.
     */
    private fun isStandardAspect(width: Int, height: Int): Boolean {
        val ratio = width.toDouble() / height.toDouble()
        return abs(ratio - ASPECT_4_3) <= TOLERANCE * ASPECT_4_3 ||
            abs(ratio - ASPECT_16_9) <= TOLERANCE * ASPECT_16_9
    }
}

/**
 * [CameraCatalog] implementation backed by the Android Camera2 API.
 *
 * Enumerates cameras via [CameraManager], maps their characteristics to
 * [CameraDescription] instances, and applies [CameraSizeFilter] to keep the
 * reported size list small and webcam-relevant.
 *
 * This class never throws from [listCameras]; individual camera failures are
 * logged and skipped.
 *
 * @param context used to obtain the system [CameraManager].
 * @param cameraManagerProvider optional override for obtaining the [CameraManager];
 *        defaults to `ContextCompat.getSystemService(context, CameraManager::class.java)`.
 *        Tests inject a fake provider to avoid static mocking of [ContextCompat].
 */
class Camera2Catalog(
    private val context: Context,
    private val cameraManagerProvider: (Context) -> CameraManager? = { ctx ->
        ContextCompat.getSystemService(ctx, CameraManager::class.java)
    },
) : CameraCatalog {

    companion object {
        private const val TAG = "Camera2Catalog"
    }

    override fun listCameras(): List<CameraDescription> {
        val manager = cameraManagerProvider(context)
        if (manager == null) {
            Log.w(TAG, "CameraManager not available on this device")
            return emptyList()
        }

        val cameraIds: Array<String> = try {
            manager.cameraIdList
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to access camera ID list", e)
            return emptyList()
        }

        val result = mutableListOf<CameraDescription>()

        for (cameraId in cameraIds) {
            try {
                val chars = manager.getCameraCharacteristics(cameraId)

                // --- facing ---
                val facingInt = chars.get(CameraCharacteristics.LENS_FACING)
                val facing = when (facingInt) {
                    CameraCharacteristics.LENS_FACING_FRONT -> CameraProtocol.FACING_FRONT
                    CameraCharacteristics.LENS_FACING_BACK -> CameraProtocol.FACING_BACK
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraProtocol.FACING_EXTERNAL
                    else -> {
                        Log.w(TAG, "Camera $cameraId has unknown facing ($facingInt), skipping")
                        continue
                    }
                }

                // --- flash ---
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

                // --- sizes ---
                val configMap: StreamConfigurationMap? =
                    chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

                val rawSizes: List<CameraSize> = if (configMap != null) {
                    val outputSizes = configMap.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                    if (outputSizes == null) {
                        emptyList()
                    } else {
                        outputSizes.mapNotNull { size ->
                            val minDuration =
                                configMap.getOutputMinFrameDuration(
                                    android.graphics.SurfaceTexture::class.java,
                                    size,
                                )
                            if (minDuration <= 0L) {
                                null
                            } else {
                                val fps = (1_000_000_000.0 / minDuration).toInt()
                                if (fps <= 0) null else CameraSize(size.width, size.height, fps)
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "Camera $cameraId: no StreamConfigurationMap, reporting empty sizes")
                    emptyList()
                }

                val sizes = CameraSizeFilter.sanitize(rawSizes)

                result.add(
                    CameraDescription(
                        id = cameraId,
                        facing = facing,
                        hasFlash = hasFlash,
                        sizes = sizes,
                    )
                )
            } catch (e: CameraAccessException) {
                Log.e(TAG, "CameraAccessException for camera $cameraId, skipping", e)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "IllegalArgumentException for camera $cameraId, skipping", e)
            }
        }

        // Sort: back first, then front, then external
        val facingOrder = mapOf(
            CameraProtocol.FACING_BACK to 0,
            CameraProtocol.FACING_FRONT to 1,
            CameraProtocol.FACING_EXTERNAL to 2,
        )
        result.sortBy { facingOrder.getOrDefault(it.facing, 3) }

        return result
    }
}

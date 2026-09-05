/*
 * SPDX-FileCopyrightText: 2026 Alfredo Medrano Sanchez <alfredomedranosanchez@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.camera

import android.Manifest
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.ui.compose.KdeButton
import org.kde.kdeconnect.ui.compose.KdeTheme
import org.kde.kdeconnect.ui.compose.KdeTopAppBar
import org.kde.kdeconnect_tp.R

/**
 * Activity for phone-initiated camera sharing.
 *
 * Presents selectors for camera, resolution, and rotation, and
 * start/stop controls. Sharing continues in the foreground service
 * even after this activity is destroyed.
 *
 * @see CameraPlugin.startSharing
 */
class CameraShareActivity : AppCompatActivity() {

    companion object {
        /** Fixed bitrate used for phone-initiated sharing (4 Mbps). */
        internal const val DEFAULT_BITRATE = 4_000_000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val deviceId = intent.getStringExtra("deviceId")
        val plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, CameraPlugin::class.java)
        if (plugin == null) {
            finish()
            return
        }

        setContent {
            KdeTheme(this) {
                CameraShareScreen(plugin = plugin, deviceId = deviceId ?: "")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraShareScreen(plugin: CameraPlugin, deviceId: String) {
    val context = LocalContext.current

    // Catalog state — loaded on a background thread
    var cameras by remember { mutableStateOf<List<CameraDescription>>(emptyList()) }
    var catalogLoaded by remember { mutableStateOf(false) }

    // Sharing state — updated via plugin listener
    var isSharing by remember { mutableStateOf(plugin.isSharing()) }

    // Selection state
    var selectedCameraIndex by rememberSaveable { mutableStateOf(0) }
    var selectedSizeIndex by rememberSaveable { mutableStateOf(0) }
    var selectedRotation by rememberSaveable { mutableStateOf(0) }

    // UI state
    var cameraExpanded by remember { mutableStateOf(false) }
    var resolutionExpanded by remember { mutableStateOf(false) }
    var rotationExpanded by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionDenied = false
            errorMessage = null
        } else {
            permissionDenied = true
        }
    }

    // Load catalog on bg thread (CameraManager.getCameraIdList needs no permission)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val catalog = Camera2Catalog(context).listCameras()
            // Default to first back-facing camera
            val backIdx = catalog.indexOfFirst { it.facing == CameraProtocol.FACING_BACK }
            cameras = catalog
            if (backIdx >= 0) {
                selectedCameraIndex = backIdx
            }
            catalogLoaded = true
        }
    }

    // Register active listener
    DisposableEffect(plugin) {
        val listener: (Boolean) -> Unit = { active ->
            isSharing = active
            if (active) errorMessage = null
        }
        plugin.addActiveListener(listener)
        onDispose {
            plugin.removeActiveListener(listener)
        }
    }

    // Derive options from selected camera
    val selectedCamera = cameras.getOrNull(selectedCameraIndex)
    // Deduplicate sizes by WxH (keep highest fps per resolution), cap at 20, sort desc by pixels
    val dedupedSizes = remember(selectedCamera) {
        selectedCamera?.sizes
            ?.groupBy { "${it.width}x${it.height}" }
            ?.values
            ?.map { group -> group.maxByOrNull { it.fps }!! }
            ?.sortedByDescending { it.width.toLong() * it.height.toLong() }
            ?.take(20)
            ?: emptyList()
    }
    // Reset size selection when camera changes
    if (selectedSizeIndex >= dedupedSizes.size) {
        selectedSizeIndex = 0
    }

    val deviceName = remember(deviceId) {
        KdeConnect.getInstance().getDevice(deviceId)?.name
    }

    fun facingLabel(facing: String): String = when (facing) {
        CameraProtocol.FACING_BACK -> context.getString(R.string.cameraplugin_facing_back)
        CameraProtocol.FACING_FRONT -> context.getString(R.string.cameraplugin_facing_front)
        CameraProtocol.FACING_EXTERNAL -> context.getString(R.string.cameraplugin_facing_external)
        else -> facing
    }

    Scaffold(
        topBar = {
            KdeTopAppBar(
                title = stringResource(R.string.cameraplugin_share_title),
                navIconOnClick = { (context as? AppCompatActivity)?.onBackPressedDispatcher?.onBackPressed() },
                navIconDescription = context.getString(androidx.appcompat.R.string.abc_action_bar_up_description),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Camera selector
            if (catalogLoaded && cameras.isNotEmpty()) {
                ExposedDropdownMenuBox(
                    expanded = cameraExpanded,
                    onExpandedChange = { if (!isSharing) cameraExpanded = it },
                ) {
                    OutlinedTextField(
                        value = cameras.getOrNull(selectedCameraIndex)?.let {
                            facingLabel(it.facing) + " (${it.id})"
                        } ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.cameraplugin_share_camera)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cameraExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        enabled = !isSharing,
                    )
                    ExposedDropdownMenu(
                        expanded = cameraExpanded,
                        onDismissRequest = { cameraExpanded = false },
                    ) {
                        cameras.forEachIndexed { index, cam ->
                            val label = facingLabel(cam.facing) + " (${cam.id})"
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    selectedCameraIndex = index
                                    selectedSizeIndex = 0
                                    cameraExpanded = false
                                },
                            )
                        }
                    }
                }
            } else if (catalogLoaded && cameras.isEmpty()) {
                Text(
                    text = stringResource(R.string.cameraplugin_share_error_no_cameras),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Resolution selector
            if (dedupedSizes.isNotEmpty()) {
                ExposedDropdownMenuBox(
                    expanded = resolutionExpanded,
                    onExpandedChange = { if (!isSharing) resolutionExpanded = it },
                ) {
                    OutlinedTextField(
                        value = dedupedSizes.getOrNull(selectedSizeIndex)?.let {
                            "${it.width}x${it.height} @ ${it.fps}"
                        } ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.cameraplugin_share_resolution)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = resolutionExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        enabled = !isSharing,
                    )
                    ExposedDropdownMenu(
                        expanded = resolutionExpanded,
                        onDismissRequest = { resolutionExpanded = false },
                    ) {
                        dedupedSizes.forEachIndexed { index, size ->
                            DropdownMenuItem(
                                text = { Text("${size.width}x${size.height} @ ${size.fps}") },
                                onClick = {
                                    selectedSizeIndex = index
                                    resolutionExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            // Rotation selector
            val rotations = listOf(0, 90, 180, 270)
            ExposedDropdownMenuBox(
                expanded = rotationExpanded,
                onExpandedChange = { if (!isSharing) rotationExpanded = it },
            ) {
                OutlinedTextField(
                    value = "${selectedRotation}\u00B0",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.cameraplugin_share_rotation)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = rotationExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    enabled = !isSharing,
                )
                ExposedDropdownMenu(
                    expanded = rotationExpanded,
                    onDismissRequest = { rotationExpanded = false },
                ) {
                    rotations.forEach { rot ->
                        DropdownMenuItem(
                            text = { Text("${rot}\u00B0") },
                            onClick = {
                                selectedRotation = rot
                                rotationExpanded = false
                            },
                        )
                    }
                }
            }

            // Status line
            if (isSharing) {
                val defaultStatus = stringResource(R.string.cameraplugin_share_status_active)
                Text(
                    text = deviceName?.let {
                        context.getString(R.string.cameraplugin_share_status_active_device, it)
                    } ?: defaultStatus,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    text = stringResource(R.string.cameraplugin_share_status_idle),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            // Error / permission message
            if (permissionDenied) {
                Text(
                    text = stringResource(R.string.cameraplugin_share_error_permission),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Start / Stop button
            val canStart = catalogLoaded && cameras.isNotEmpty()
            KdeButton(
                onClick = {
                    if (!canStart) return@KdeButton
                    if (isSharing) {
                        plugin.stopSharing()
                    } else {
                        // Check permission
                        val hasPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                            context, Manifest.permission.CAMERA
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (!hasPerm) {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                            return@KdeButton
                        }

                        val cam = cameras.getOrNull(selectedCameraIndex) ?: return@KdeButton
                        val size = dedupedSizes.getOrNull(selectedSizeIndex) ?: return@KdeButton
                        val request = CameraSession.Request(
                            cameraId = cam.id,
                            width = size.width,
                            height = size.height,
                            fps = size.fps,
                            bitrate = CameraShareActivity.DEFAULT_BITRATE,
                            rotationDegrees = selectedRotation,
                        )
                        if (!plugin.startSharing(request)) {
                            errorMessage = context.getString(R.string.cameraplugin_share_error_busy)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                text = stringResource(
                    if (isSharing) R.string.cameraplugin_share_stop
                    else R.string.cameraplugin_share_start
                ),
            )
        }
    }
}

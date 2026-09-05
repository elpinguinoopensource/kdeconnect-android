/*
 * SPDX-FileCopyrightText: 2026 Alfredo Medrano Sanchez <alfredomedranosanchez@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.kde.kdeconnect.KdeConnect

class CameraNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP) return
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return
        val plugin = KdeConnect.getInstance()
            .getDevicePlugin(deviceId, CameraPlugin::class.java) ?: return
        plugin.stopSession(userInitiated = true)
    }

    companion object {
        const val ACTION_STOP = "org.kde.kdeconnect.CAMERA_STOP"
        const val EXTRA_DEVICE_ID = "deviceId"
    }
}

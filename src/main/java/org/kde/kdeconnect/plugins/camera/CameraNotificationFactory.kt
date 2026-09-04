/*
 * SPDX-FileCopyrightText: 2026 Alfredo Medrano Sanchez <alfredomedranosanchez@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.camera

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect_tp.R

object CameraNotificationFactory {

    fun build(context: Context, deviceName: String, deviceId: String): Notification {
        val stopIntent = Intent(context, CameraNotificationReceiver::class.java)
            .setAction(CameraNotificationReceiver.ACTION_STOP)
            .putExtra(CameraNotificationReceiver.EXTRA_DEVICE_ID, deviceId)
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            3, // requestCode unique in app (MPRIS uses 0, 1, 2)
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, NotificationHelper.Channels.HIGHPRIORITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.cameraplugin_notification_title))
            .setContentText(context.getString(R.string.cameraplugin_notification_text, deviceName))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup("BackgroundService")
            .addAction(0, context.getString(R.string.cameraplugin_notification_stop), stopPendingIntent)
            .build()
    }
}

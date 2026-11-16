package com.example.claudewake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context

object Notify {
    private const val CH = "claudewake"

    fun post(c: Context, id: Int, title: String, text: String, full: PendingIntent? = null) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "Claude Wake", NotificationManager.IMPORTANCE_HIGH)
        )
        val b = Notification.Builder(c, CH)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
        if (full != null) b.setFullScreenIntent(full, true)
        try { nm.notify(id, b.build()) } catch (_: SecurityException) {}
    }
}

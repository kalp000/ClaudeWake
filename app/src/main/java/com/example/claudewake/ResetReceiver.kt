package com.example.claudewake

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

class ResetReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.enabled(context)) return

        // Turn the screen on.
        val pm = context.getSystemService(PowerManager::class.java)
        pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE, "claudewake:wake"
        ).acquire(90_000)

        Prefs.setStatus(context, "Limit ended, waking phone")

        // Full-screen intent is the reliable way to show an activity from the background.
        val wake = Intent(context, WakeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            context, 2, wake, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        Notify.post(context, 10, "Claude limit ended", "Sending your saved message…", pi)
        try { context.startActivity(wake) } catch (_: Exception) {}
    }
}

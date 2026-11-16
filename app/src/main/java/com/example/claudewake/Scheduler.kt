package com.example.claudewake

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.DateFormat
import java.util.Date

object Scheduler {
    private const val ACTION = "com.example.claudewake.RESET"
    private const val BUFFER_MS = 60_000L // wake 1 min after the stated reset time

    private fun pending(c: Context) = PendingIntent.getBroadcast(
        c, 1, Intent(c, ResetReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(c: Context, resetAt: Long) {
        val am = c.getSystemService(AlarmManager::class.java)
        val t = resetAt + BUFFER_MS
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pending(c))
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pending(c))
        }
        Prefs.setResetAt(c, resetAt)
        Prefs.setStatus(c, "Waiting. Will wake Claude at " +
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t)))
    }

    fun cancel(c: Context) {
        c.getSystemService(AlarmManager::class.java).cancel(pending(c))
        Prefs.setResetAt(c, 0L)
    }
}

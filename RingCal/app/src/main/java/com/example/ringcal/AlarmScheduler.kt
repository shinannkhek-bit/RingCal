package com.example.ringcal

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** Schedules exact "alarm clock" alarms, which fire even in battery-saving (Doze) mode. */
object AlarmScheduler {
    const val EXTRA_ID = "event_id"

    private fun pending(ctx: Context, id: Long): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .setAction("com.example.ringcal.ALARM")
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            ctx, id.toInt(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    fun schedule(ctx: Context, id: Long, atMillis: Long) {
        if (atMillis <= System.currentTimeMillis()) return
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, id)
        try {
            val show = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, show), pi)
        } catch (e: SecurityException) {
            // Exact alarms not allowed: fall back to a less precise alarm.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    fun cancel(ctx: Context, id: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, id))
    }

    fun rescheduleAll(ctx: Context) {
        for (e in EventStore.load(ctx)) schedule(ctx, e.id, e.alarmMillis)
    }
}

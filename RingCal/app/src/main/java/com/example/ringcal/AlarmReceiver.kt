package com.example.ringcal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Called by Android at alarm time; starts the ringing service. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(AlarmScheduler.EXTRA_ID, -1L)
        if (id < 0) return
        val svc = Intent(ctx, RingService::class.java)
            .setAction(RingService.ACTION_RING)
            .putExtra(AlarmScheduler.EXTRA_ID, id)
        ctx.startForegroundService(svc)
    }
}

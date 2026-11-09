package com.example.ringcal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Alarms are wiped when the phone restarts, so set them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        AlarmScheduler.rescheduleAll(ctx)
    }
}

package com.example.ringcal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Plays the alarm sound on a loop and vibrates until Snooze or Dismiss is pressed. */
class RingService : Service() {

    companion object {
        const val ACTION_RING = "com.example.ringcal.RING"
        const val ACTION_SNOOZE = "com.example.ringcal.SNOOZE"
        const val ACTION_DISMISS = "com.example.ringcal.DISMISS"
        const val CHANNEL = "ringcal_alarm"
        const val NOTIF_ID = 4242
        const val SNOOZE_MINUTES = 5   // change this to set the snooze length
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(AlarmScheduler.EXTRA_ID, -1L) ?: -1L
        when (intent?.action) {
            ACTION_RING -> ring(id)
            ACTION_SNOOZE -> {
                if (id >= 0) AlarmScheduler.schedule(this, id, System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L)
                stopRinging()
            }
            ACTION_DISMISS -> {
                // Restores the event's real alarm if it is still in the future (e.g. after a test ring).
                if (id >= 0) EventStore.get(this, id)?.let { AlarmScheduler.schedule(this, it.id, it.alarmMillis) }
                stopRinging()
            }
            else -> stopRinging()
        }
        return START_NOT_STICKY
    }

    private fun ring(id: Long) {
        stopSound()
        val ev = if (id >= 0) EventStore.get(this, id) else null
        val title = ev?.title ?: "Reminder"
        val whenText = ev?.let {
            "Starts " + DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
                .format(Instant.ofEpochMilli(it.startMillis).atZone(ZoneId.systemDefault()))
        } ?: ""

        ensureChannel()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val full = PendingIntent.getActivity(
            this, 0,
            Intent(this, AlarmActivity::class.java)
                .putExtra(AlarmScheduler.EXTRA_ID, id)
                .putExtra(AlarmActivity.EXTRA_TITLE, title)
                .putExtra(AlarmActivity.EXTRA_WHEN, whenText)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )
        val snooze = PendingIntent.getService(
            this, 1,
            Intent(this, RingService::class.java).setAction(ACTION_SNOOZE).putExtra(AlarmScheduler.EXTRA_ID, id),
            flags
        )
        val dismiss = PendingIntent.getService(
            this, 2,
            Intent(this, RingService::class.java).setAction(ACTION_DISMISS).putExtra(AlarmScheduler.EXTRA_ID, id),
            flags
        )

        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(whenText)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(Notification.Action.Builder(null as Icon?, "Snooze $SNOOZE_MINUTES min", snooze).build())
            .addAction(Notification.Action.Builder(null as Icon?, "Dismiss", dismiss).build())
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
        startSound()
        startVibration()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Event alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings until you snooze or dismiss"
                setSound(null, null)          // the service plays the sound itself, on a loop
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun startSound() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val candidates = listOfNotNull(
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        )
        for (uri in candidates) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attrs)
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
                mp.prepare()
                mp.start()
                player = mp
                return
            } catch (e: Exception) {
                mp.release()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator = v
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0), attrs)
    }

    private fun stopSound() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun stopRinging() {
        stopSound()
        AlarmActivity.current?.finish()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        stopSelf()
    }

    override fun onDestroy() {
        stopSound()
        super.onDestroy()
    }
}

package com.example.ringcal

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Full-screen alarm shown over the lock screen with big Snooze / Dismiss buttons. */
class AlarmActivity : Activity() {

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_WHEN = "when"
        var current: AlarmActivity? = null
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val id = intent.getLongExtra(AlarmScheduler.EXTRA_ID, -1L)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply { text = "⏰"; textSize = 64f; gravity = Gravity.CENTER })
        root.addView(TextView(this).apply {
            text = intent.getStringExtra(EXTRA_TITLE) ?: "Reminder"
            textSize = 28f; gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = intent.getStringExtra(EXTRA_WHEN) ?: ""
            textSize = 18f; gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad)
        })

        fun bigButton(label: String, action: String) = Button(this).apply {
            text = label
            textSize = 20f
            setOnClickListener {
                startService(
                    Intent(this@AlarmActivity, RingService::class.java)
                        .setAction(action)
                        .putExtra(AlarmScheduler.EXTRA_ID, id)
                )
                finish()
            }
        }
        root.addView(bigButton("Snooze ${RingService.SNOOZE_MINUTES} min", RingService.ACTION_SNOOZE),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(bigButton("Dismiss", RingService.ACTION_DISMISS),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }
}

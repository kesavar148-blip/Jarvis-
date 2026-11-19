package com.jarvis.assistant

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.core.content.ContextCompat

/** The screen that appears over the lock screen while an alarm rings. Snooze or Dismiss. */
class AlarmActivity : Activity() {

    private val ended = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        val r = ReminderDb.of(this).find(intent.getLongExtra(AlarmScheduler.EXTRA_ID, -1L))
        if (r == null || r.status != Status.RINGING) {
            finish()
            return
        }
        val snoozeMin = getSharedPreferences(Config.PREFS, MODE_PRIVATE)
            .getInt(Config.PREF_SNOOZE_MIN, Config.DEFAULT_SNOOZE_MIN).coerceIn(1, 60)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setPadding(dp(24), dp(24), dp(24), dp(24))
        root.setBackgroundColor(Color.parseColor("#030B14"))

        val clock = TextClock(this)
        clock.format12Hour = "h:mm a"
        clock.format24Hour = "h:mm a"
        clock.textSize = 56f
        clock.gravity = Gravity.CENTER
        clock.setTextColor(Color.parseColor("#00E5FF"))
        root.addView(clock)

        val title = TextView(this)
        title.text = if (r.title.isBlank()) Reminders.defaultTitle(r.kind) else r.title
        title.textSize = 26f
        title.gravity = Gravity.CENTER
        title.setTextColor(Color.WHITE)
        title.setPadding(0, dp(12), 0, dp(40))
        root.addView(title)

        root.addView(bigButton("Snooze $snoozeMin min") { send(AlarmService.ACTION_SNOOZE) })
        root.addView(bigButton("Dismiss") { send(AlarmService.ACTION_DISMISS) })
        setContentView(root)

        ContextCompat.registerReceiver(
            this, ended, IntentFilter(AlarmService.ACTION_ENDED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(ended)
        } catch (e: IllegalArgumentException) {
            // never registered (the screen closed early)
        }
        super.onDestroy()
    }

    private fun send(action: String) {
        startService(Intent(this, AlarmService::class.java).setAction(action))
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bigButton(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 22f
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(76))
        lp.topMargin = dp(14)
        b.layoutParams = lp
        return b
    }
}

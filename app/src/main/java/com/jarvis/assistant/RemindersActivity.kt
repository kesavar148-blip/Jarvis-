package com.jarvis.assistant

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Dashboard of alarms, reminders and timers, with a one-minute test alarm and the snooze setting. */
class RemindersActivity : ComponentActivity() {

    private lateinit var info: TextView
    private lateinit var list: LinearLayout
    private lateinit var snoozeBox: EditText
    private lateinit var fullScreenButton: Button

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------- Actions ----------

    private fun prefs() = getSharedPreferences(Config.PREFS, MODE_PRIVATE)

    private fun onTestAlarm() {
        val out = Reminders.create(
            this, "Test alarm", Kind.ALARM, System.currentTimeMillis() + 60_000L, Repeat.NONE
        )
        toast(out.spoken + " Lock the screen and wait one minute.")
        refresh()
    }

    private fun onNewAlarm() {
        TimePickerDialog(this, { _, hour, minute -> askRepeat(hour, minute) }, 7, 0, false).show()
    }

    private fun askRepeat(hour: Int, minute: Int) {
        AlertDialog.Builder(this)
            .setTitle("Repeat")
            .setItems(arrayOf("Once", "Every day", "Weekdays")) { _, which ->
                val repeat = when (which) {
                    1 -> Repeat.DAILY
                    2 -> Repeat.WEEKDAYS
                    else -> Repeat.NONE
                }
                val at = TimeParser.nextAt(hour, minute, System.currentTimeMillis(), repeat == Repeat.WEEKDAYS)
                toast(Reminders.create(this, "", Kind.ALARM, at, repeat).spoken)
                refresh()
            }
            .show()
    }

    private fun onSaveSnooze() {
        val n = snoozeBox.text.toString().trim().toIntOrNull()
        if (n == null || n !in 1..60) {
            toast("Enter a number from 1 to 60.")
            return
        }
        prefs().edit().putInt(Config.PREF_SNOOZE_MIN, n).apply()
        toast("Snooze is now $n minutes.")
    }

    private fun onFullScreenSettings() {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------- Screen ----------

    private fun refresh() {
        val notificationsOn = NotificationManagerCompat.from(this).areNotificationsEnabled()
        var screenOk = true
        if (Build.VERSION.SDK_INT >= 34) {
            screenOk = getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        }
        val lines = ArrayList<String>()
        lines.add(if (notificationsOn) "Notifications: allowed" else "Notifications: BLOCKED. Alarms need them. Open app settings.")
        lines.add(
            if (screenOk) "Alarm screen over the lock screen: allowed"
            else "Alarm screen over the lock screen: not allowed yet. The alarm will still ring."
        )
        info.text = lines.joinToString("\n")
        fullScreenButton.visibility = if (screenOk) android.view.View.GONE else android.view.View.VISIBLE

        list.removeAllViews()
        val items = ReminderDb.of(this).forScreen()
        if (items.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Nothing scheduled. Say: Hey Jarvis, wake me up tomorrow at 8 AM."
                setTextColor(Color.parseColor("#88AABB"))
            })
        }
        for (r in items) list.addView(row(r))
    }

    private fun row(r: Reminder): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(8), 0, dp(8))

        val text = TextView(this)
        val status = if (r.status == Status.MISSED) " (missed)" else if (r.status == Status.RINGING) " (ringing)" else ""
        text.text = Reminders.label(r.kind).replaceFirstChar { it.uppercase() } + ": " + Reminders.describe(r) + status
        text.setTextColor(if (r.status == Status.MISSED) Color.parseColor("#FF6E6E") else Color.WHITE)
        text.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(text)

        val b = Button(this)
        b.text = if (r.status == Status.MISSED) "Clear" else "Cancel"
        b.isAllCaps = false
        b.setOnClickListener {
            Reminders.cancel(this, r.id)
            refresh()
        }
        row.addView(b)
        return row
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(10)
        b.layoutParams = lp
        return b
    }

    private fun label(s: String): TextView {
        val t = TextView(this)
        t.text = s
        t.setTextColor(Color.parseColor("#88AABB"))
        t.setPadding(0, dp(16), 0, dp(4))
        return t
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(24), dp(40), dp(24), dp(24))
        root.setBackgroundColor(Color.parseColor("#030B14"))

        val title = TextView(this)
        title.text = "REMINDERS & ALARMS"
        title.textSize = 22f
        title.letterSpacing = 0.15f
        title.gravity = Gravity.CENTER
        title.setTextColor(Color.parseColor("#00E5FF"))
        root.addView(title)

        info = TextView(this)
        info.setTextColor(Color.parseColor("#88AABB"))
        info.setPadding(0, dp(16), 0, 0)
        root.addView(info)

        fullScreenButton = button("Allow alarm screen over lock screen") { onFullScreenSettings() }
        root.addView(fullScreenButton)
        root.addView(button("Test alarm in 1 minute") { onTestAlarm() })
        root.addView(button("New alarm") { onNewAlarm() })

        root.addView(label("Snooze length (minutes)"))
        snoozeBox = EditText(this)
        snoozeBox.inputType = InputType.TYPE_CLASS_NUMBER
        snoozeBox.setText(prefs().getInt(Config.PREF_SNOOZE_MIN, Config.DEFAULT_SNOOZE_MIN).toString())
        snoozeBox.setTextColor(Color.WHITE)
        root.addView(snoozeBox)
        root.addView(button("Save snooze length") { onSaveSnooze() })

        root.addView(label("Scheduled"))
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        root.addView(list)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }
}

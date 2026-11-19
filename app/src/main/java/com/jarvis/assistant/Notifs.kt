package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** Notification channels and the "you missed something" notification. */
object Notifs {

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(Config.CHANNEL_SERVICE, "Jarvis running", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(Config.CHANNEL_ACTION, "Jarvis actions", NotificationManager.IMPORTANCE_HIGH)
        )
        val alarm = NotificationChannel(
            Config.CHANNEL_ALARM, "Alarms and reminders", NotificationManager.IMPORTANCE_HIGH
        )
        alarm.description = "Shown while an alarm, reminder or timer is ringing"
        alarm.setSound(null, null) // the alarm service plays its own, louder sound
        alarm.enableVibration(false)
        alarm.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(alarm)
        nm.createNotificationChannel(
            NotificationChannel(Config.CHANNEL_MISSED, "Missed alarms", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    /** "Sir, you missed ..." Posted when an alarm rang unanswered or the phone was off at its time. */
    fun missed(ctx: Context, r: Reminder) {
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(
            ctx, 20,
            Intent(ctx, RemindersActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val what = if (r.title.isBlank()) Reminders.defaultTitle(r.kind) else r.title
        val n = NotificationCompat.Builder(ctx, Config.CHANNEL_MISSED)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Sir, you missed your " + Reminders.label(r.kind))
            .setContentText(what + ", due " + TimeFmt.humanWhen(r.triggerAt))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val id = Config.NOTIF_MISSED_BASE + (r.id % 100_000L).toInt()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    /** Last resort when the ring service cannot start: at least show a loud notification. */
    fun dueNow(ctx: Context, r: Reminder) {
        ensureChannels(ctx)
        val what = if (r.title.isBlank()) Reminders.defaultTitle(r.kind) else r.title
        val n = NotificationCompat.Builder(ctx, Config.CHANNEL_MISSED)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Sir, your " + Reminders.label(r.kind) + " is due")
            .setContentText(what)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()
        val id = Config.NOTIF_MISSED_BASE + (r.id % 100_000L).toInt()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}

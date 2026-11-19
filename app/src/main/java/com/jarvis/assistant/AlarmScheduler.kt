package com.jarvis.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Hands alarms to Android's AlarmManager.
 *
 * setAlarmClock() is used for everything. It is exact, it wakes the phone from Doze (screen off, idle),
 * and unlike setExact() it needs NO special "alarms and reminders" permission, which Android 14 turns
 * off by default for apps installed outside the Play Store. The price: Android shows a small alarm
 * icon in the status bar while one is scheduled.
 */
object AlarmScheduler {
    const val ACTION_FIRE = "com.jarvis.assistant.ALARM_FIRE"
    const val EXTRA_ID = "reminder_id"

    fun schedule(ctx: Context, r: Reminder) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, RemindersActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(r.triggerAt, show), firePendingIntent(ctx, r.id))
    }

    fun cancel(ctx: Context, id: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(firePendingIntent(ctx, id))
    }

    private fun firePendingIntent(ctx: Context, id: Long): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            ctx, (id % Int.MAX_VALUE.toLong()).toInt(), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * Android forgets all alarms when the phone restarts, so this puts them back.
     * Anything whose time passed while the phone was off is reported as missed (a repeating one is
     * scheduled for its next time). Returns how many were missed.
     *
     * @param afterRestart true after boot or app update: a reminder still marked RINGING then really was interrupted.
     */
    fun restoreAll(ctx: Context, afterRestart: Boolean): Int {
        val db = ReminderDb.of(ctx)
        val now = System.currentTimeMillis()
        var missed = 0
        for (r in db.active()) {
            if (r.status == Status.RINGING && !afterRestart) continue // ringing right now, leave it alone
            if (r.status == Status.PENDING && r.triggerAt > now) {
                schedule(ctx, r)
                continue
            }
            // Due a moment ago (the phone was just starting up): ring now instead of reporting it missed.
            if (r.status == Status.PENDING && now - r.triggerAt <= 120_000L) {
                val soon = r.copy(triggerAt = now + 3_000L)
                db.update(soon)
                schedule(ctx, soon)
                continue
            }
            if (r.kind != Kind.TIMER) {
                missed++
                Notifs.missed(ctx, r)
            }
            val next = Schedule.nextAfter(r.baseAt, r.repeat, now)
            if (next != null) {
                val nr = r.copy(baseAt = next, triggerAt = next, status = Status.PENDING, snoozeCount = 0)
                db.update(nr)
                schedule(ctx, nr)
            } else {
                db.update(r.copy(status = if (r.kind == Kind.TIMER) Status.DONE else Status.MISSED))
            }
        }
        return missed
    }
}

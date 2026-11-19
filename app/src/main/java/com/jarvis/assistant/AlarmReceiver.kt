package com.jarvis.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/** Android wakes this at the exact alarm time, even with the screen off. It starts the ring service. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(AlarmScheduler.EXTRA_ID, -1L)
        if (id < 0L) return
        val svc = Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_RING)
            .putExtra(AlarmScheduler.EXTRA_ID, id)
        try {
            ContextCompat.startForegroundService(context, svc)
        } catch (e: Exception) {
            Log.e("AlarmReceiver", "Could not start the ring service", e)
            ReminderDb.of(context).find(id)?.let { Notifs.dueNow(context, it) }
        }
    }
}

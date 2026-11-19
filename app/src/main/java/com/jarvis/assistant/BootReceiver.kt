package com.jarvis.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Runs after the phone restarts, the app is updated, or the clock or time zone changes,
 * and puts every saved alarm back into Android's AlarmManager.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Notifs.ensureChannels(context)
        val afterRestart = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        AlarmScheduler.restoreAll(context, afterRestart)
    }
}

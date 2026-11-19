package com.jarvis.assistant

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.Locale

/**
 * Rings an alarm, reminder or timer: loud looping alarm sound, vibration, a spoken message every
 * few seconds, a lock-screen screen with Snooze and Dismiss, and a wake lock so the phone stays awake.
 *
 * If nobody answers: an alarm snoozes itself up to [MAX_AUTO_SNOOZES] times, then is reported as missed.
 * Snooze length comes from the Reminders screen (default 5 minutes).
 */
class AlarmService : Service() {

    companion object {
        const val ACTION_RING = "com.jarvis.assistant.RING"
        const val ACTION_SNOOZE = "com.jarvis.assistant.SNOOZE"
        const val ACTION_DISMISS = "com.jarvis.assistant.DISMISS"
        const val ACTION_ENDED = "com.jarvis.assistant.RING_ENDED"
        const val MAX_AUTO_SNOOZES = 3
        private const val TAG = "AlarmService"
    }

    private enum class Outcome { DISMISSED, SNOOZED, TIMEOUT }

    private class Profile(val timeoutMs: Long, val speakEveryMs: Long)

    private val main = Handler(Looper.getMainLooper())
    private var current: Reminder? = null
    private var profile = Profile(180_000L, 25_000L)
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val speakTick = object : Runnable {
        override fun run() {
            speakLine()
            main.postDelayed(this, profile.speakEveryMs)
        }
    }

    private val timeoutRun = Runnable { finishRing(Outcome.TIMEOUT) }

    override fun onCreate() {
        super.onCreate()
        Notifs.ensureChannels(this)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts
                if (t != null) {
                    val res = t.setLanguage(Locale("en", "IN"))
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        t.setLanguage(Locale.US)
                    }
                    t.setPitch(0.9f)
                    ttsReady = true
                }
            } else {
                Log.e(TAG, "TextToSpeech init failed: $status")
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                main.post { player?.setVolume(1f, 1f) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { player?.setVolume(1f, 1f) }
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val id = intent?.getLongExtra(AlarmScheduler.EXTRA_ID, -1L) ?: -1L
        when (action) {
            ACTION_RING -> ring(id)
            ACTION_SNOOZE -> finishRing(Outcome.SNOOZED)
            ACTION_DISMISS -> finishRing(Outcome.DISMISSED)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------- Ringing ----------

    private fun ring(id: Long) {
        val db = ReminderDb.of(this)
        val r = if (id >= 0L) db.find(id) else null
        val ringing = if (r != null && (r.status == Status.PENDING || r.status == Status.RINGING)) {
            r.copy(status = Status.RINGING)
        } else {
            null
        }
        // Mark it ringing BEFORE the notification appears, because the lock-screen screen checks this when it opens.
        if (ringing != null) db.update(ringing)
        // Android requires startForeground() soon after startForegroundService(), even if there is nothing to ring.
        startForegroundSafe(buildNotification(r))
        if (ringing == null) {
            stopForegroundAndSelf()
            return
        }
        val prev = current
        if (prev != null && prev.id == ringing.id) return // already ringing
        if (prev != null) settle(Outcome.TIMEOUT) // a newer alarm takes over
        current = ringing
        profile = when (ringing.kind) {
            Kind.ALARM -> Profile(180_000L, 25_000L)
            Kind.TIMER -> Profile(60_000L, 20_000L)
            Kind.REMINDER -> Profile(45_000L, 20_000L)
        }
        acquireWakeLock(profile.timeoutMs + 20_000L)
        startSound()
        startVibration()
        main.postDelayed(speakTick, 3_000L)
        main.postDelayed(timeoutRun, profile.timeoutMs)
    }

    private fun speakLine() {
        val r = current ?: return
        val t = tts
        if (t == null || !ttsReady) return
        val line = TimeFmt.ringLine(r.kind, r.title, System.currentTimeMillis())
        player?.setVolume(0.2f, 0.2f) // lower the alarm while Jarvis talks
        val params = Bundle()
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
        t.speak(line, TextToSpeech.QUEUE_FLUSH, params, "ring")
    }

    private fun startSound() {
        try {
            val am = getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
                // An alarm that is silent because the alarm volume is 0 is useless. Raise it to 60%.
                try {
                    am.setStreamVolume(AudioManager.STREAM_ALARM, (max * 0.6f).toInt().coerceAtLeast(1), 0)
                } catch (e: SecurityException) {
                    Log.w(TAG, "Could not raise alarm volume", e)
                }
            }
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            Log.e(TAG, "Could not play the alarm sound", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator = v
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 800L, 600L), 0))
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }
    }

    // ---------- Ending ----------

    private fun finishRing(outcome: Outcome) {
        settle(outcome)
        sendBroadcast(Intent(ACTION_ENDED).setPackage(packageName)) // closes the lock-screen screen
        stopForegroundAndSelf()
    }

    /** Stops the noise and records what happened to the reminder in the database. */
    private fun settle(outcome: Outcome) {
        val r = current ?: return
        current = null
        stopEffects()
        val now = System.currentTimeMillis()
        when (outcome) {
            Outcome.SNOOZED -> snooze(r, now)
            Outcome.DISMISSED -> complete(r, now, false)
            Outcome.TIMEOUT -> when {
                r.kind == Kind.ALARM && r.snoozeCount < MAX_AUTO_SNOOZES -> snooze(r, now)
                r.kind == Kind.TIMER -> complete(r, now, false)
                else -> complete(r, now, true)
            }
        }
    }

    private fun snooze(r: Reminder, now: Long) {
        val minutes = getSharedPreferences(Config.PREFS, MODE_PRIVATE)
            .getInt(Config.PREF_SNOOZE_MIN, Config.DEFAULT_SNOOZE_MIN).coerceIn(1, 60)
        val next = r.copy(
            triggerAt = now + minutes * 60_000L,
            status = Status.PENDING,
            snoozeCount = r.snoozeCount + 1
        )
        ReminderDb.of(this).update(next)
        AlarmScheduler.schedule(this, next)
    }

    private fun complete(r: Reminder, now: Long, missed: Boolean) {
        val db = ReminderDb.of(this)
        val nextTime = Schedule.nextAfter(r.baseAt, r.repeat, now)
        if (nextTime != null) {
            val next = r.copy(baseAt = nextTime, triggerAt = nextTime, status = Status.PENDING, snoozeCount = 0)
            db.update(next)
            AlarmScheduler.schedule(this, next)
        } else {
            db.update(r.copy(status = if (missed) Status.MISSED else Status.DONE))
        }
        if (missed) Notifs.missed(this, r)
    }

    private fun stopEffects() {
        main.removeCallbacks(speakTick)
        main.removeCallbacks(timeoutRun)
        try {
            player?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "player stop", e)
        }
        player?.release()
        player = null
        vibrator?.cancel()
        vibrator = null
        tts?.stop()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- Notification, wake lock ----------

    private fun startForegroundSafe(n: Notification) {
        try {
            ServiceCompat.startForeground(
                this, Config.NOTIF_ALARM, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } catch (e: Exception) {
            // The alarm still rings; only the notification is missing.
            Log.e(TAG, "startForeground failed", e)
        }
    }

    private fun buildNotification(r: Reminder?): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val screen = PendingIntent.getActivity(
            this, 10,
            Intent(this, AlarmActivity::class.java)
                .putExtra(AlarmScheduler.EXTRA_ID, r?.id ?: -1L)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            flags
        )
        val snooze = PendingIntent.getService(
            this, 11, Intent(this, AlarmService::class.java).setAction(ACTION_SNOOZE), flags
        )
        val dismiss = PendingIntent.getService(
            this, 12, Intent(this, AlarmService::class.java).setAction(ACTION_DISMISS), flags
        )
        val title = if (r == null) "Jarvis" else if (r.title.isBlank()) Reminders.defaultTitle(r.kind) else r.title
        return NotificationCompat.Builder(this, Config.CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(TimeFmt.spoken(System.currentTimeMillis()))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .addAction(0, "Snooze", snooze)
            .addAction(0, "Dismiss", dismiss)
            .build()
    }

    private fun acquireWakeLock(ms: Long) {
        val pm = getSystemService(PowerManager::class.java)
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::Alarm")
        lock.setReferenceCounted(false)
        lock.acquire(ms)
        wakeLock = lock
    }

    override fun onDestroy() {
        stopEffects()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}

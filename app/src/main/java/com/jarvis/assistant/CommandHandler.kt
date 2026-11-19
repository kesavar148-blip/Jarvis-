package com.jarvis.assistant

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns what you said into an action and a short spoken reply.
 * handle() returns the sentence Jarvis should say ("" for silence).
 */
class CommandHandler(
    private val ctx: Context,
    private val say: (String) -> Unit,
    private val quit: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var torchOn = false

    private val jokes = listOf(
        "Why did the computer go to the doctor? Because it caught a virus.",
        "Why was the math book sad? It had too many problems.",
        "What do you call a sleeping bull? A bulldozer.",
        "Why did the phone wear glasses? Because it lost its contacts."
    )
    private val stories = listOf(
        "Benny the bunny found one big carrot. A hungry squirrel came by, so Benny shared half. " +
            "The squirrel shared his nuts back, and they became best friends. Sharing makes everyone happy.",
        "A little ant found a grain of rice, too heavy to carry. Her friends came to help, " +
            "and together they carried it home. Teamwork makes hard things easy.",
        "A boy found a wallet on the road. He returned it to the owner, who smiled and thanked him. " +
            "Honesty is always the best choice."
    )

    fun handle(raw: String): String {
        var t = raw.lowercase(Locale.ROOT).trim()
        t = t.replace(Regex("^(hey |ok |okay )?jarvis[, ]*"), "").trim()
        t = numberWordsToDigits(t)
        if (t.isEmpty()) return "Yes? I am listening."

        if (Regex("^(hi|hello|hey)\\b").containsMatchIn(t)) return "Hello! How can I help you?"
        if (t.contains("how are you")) return "I am running at full power. How can I help?"
        if (t.contains("who are you") || t.contains("your name")) return "I am Jarvis, your voice assistant."
        if (t.contains("thank")) return "You are welcome."
        if (Regex("(goodbye|good bye|shut down|turn yourself off|stop listening)").containsMatchIn(t)) {
            main.postDelayed({ quit() }, 2500)
            return "Goodbye. Shutting down."
        }

        // Alarms, reminders, timers and tasks. Must come before the time and date answers below,
        // because "remind me today at 5" contains the word "today".
        scheduleCommand(t)?.let { return it }

        if (Regex("\\btime\\b").containsMatchIn(t)) {
            return "It is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) + "."
        }
        if (Regex("\\b(date|day|today)\\b").containsMatchIn(t) && !t.contains("weather")) {
            return "Today is " + SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date()) + "."
        }
        if (t.contains("battery")) {
            return batteryText()
        }

        if (t.contains("joke")) return jokes.random()
        if (t.contains("story")) return stories.random()

        if (t.contains("flashlight") || t.contains("torch")) {
            val on = !Regex("\\b(off|disable|close)\\b").containsMatchIn(t)
            return setTorch(on)
        }
        if (t.contains("volume") || t.contains("louder") || t.contains("quieter")) return changeVolume(t)

        val ytPlay = Regex("(?:play|youtube)\\s+(.+)").find(t)
        if (ytPlay != null && t != "open youtube") {
            val q = ytPlay.groupValues[1].replace(" on youtube", "").trim()
            return spokenLaunch("https://www.youtube.com/results?search_query=" + enc(q), "YouTube search for $q")
        }
        if (t.contains("open youtube")) return spokenLaunch("https://www.youtube.com", "YouTube")
        if (t.contains("open whatsapp")) return spokenLaunch("https://wa.me/", "WhatsApp")
        if (Regex("open (gmail|mail)").containsMatchIn(t)) return spokenLaunch("https://mail.google.com", "Gmail")
        if (t.contains("open maps")) return spokenLaunch("https://maps.google.com", "Maps")
        if (t.contains("news")) return spokenLaunch("https://news.google.com", "the news")
        if (t.contains("weather")) {
            return spokenLaunch("https://www.google.com/search?q=" + enc(t), "the weather")
        }

        val search = Regex("(?:search|google|look up|find)\\s+(?:for\\s+)?(.+)").find(t)
        if (search != null) {
            val q = search.groupValues[1]
            return spokenLaunch("https://www.google.com/search?q=" + enc(q), "a Google search for $q")
        }
        if (Regex("^(what|who|when|where|why|how|which)\\b").containsMatchIn(t)) {
            return spokenLaunch("https://www.google.com/search?q=" + enc(t), "a Google search")
        }

        return "I did not understand that. Try: what time is it, battery level, torch on, set a timer for 5 minutes, or play songs on YouTube."
    }

    // ---------- Actions ----------

    fun setTorch(on: Boolean): String {
        return try {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "This phone has no flashlight."
            cm.setTorchMode(id, on)
            torchOn = on
            if (on) "Flashlight on." else "Flashlight off."
        } catch (e: Exception) {
            "I could not control the flashlight."
        }
    }

    fun changeVolume(t: String): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_MUSIC
        return when {
            Regex("(up|increase|louder|raise)").containsMatchIn(t) -> {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0); "Volume up."
            }
            Regex("(down|decrease|quieter|lower|reduce)").containsMatchIn(t) -> {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0); "Volume down."
            }
            t.contains("max") -> {
                am.setStreamVolume(stream, am.getStreamMaxVolume(stream), 0); "Volume at maximum."
            }
            t.contains("mute") -> {
                am.setStreamVolume(stream, 0, 0); ""
            }
            else -> "Say volume up or volume down."
        }
    }

    /**
     * Offline handling of alarms, reminders, timers and tasks. Returns null when the sentence is about something else.
     * Everything here is saved on the phone and scheduled with Android's AlarmManager, so it still
     * rings when Jarvis is not running and the screen is off.
     */
    private fun scheduleCommand(t: String): String? {
        // --- to-do list ---
        Regex("^(?:add|create|make|new) (?:a |an )?(?:task|to-?do|todo)(?: to| for| called)?\\s*(.*)$").find(t)?.let {
            return Reminders.addTask(ctx, it.groupValues[1])
        }
        Regex("^add (.+) to (?:my )?(?:tasks|to-?do list|todo list|to do list)$").find(t)?.let {
            return Reminders.addTask(ctx, it.groupValues[1])
        }
        if (Regex("\\btask\\b").containsMatchIn(t) && Regex("^(complete|finish|mark|done|tick)").containsMatchIn(t)) {
            return Reminders.completeTask(ctx, t)
        }
        if (Regex("\\b(pending|remaining|open|my) (tasks|to-?dos?)\\b|\\bto-?do list\\b|\\bwhat tasks\\b").containsMatchIn(t)) {
            return Reminders.spokenTasks(ctx)
        }

        // --- list or cancel ---
        if (Regex("\\b(what|which|list|show|tell)\\b.*\\b(alarms?|reminders?)\\b|what do i have scheduled").containsMatchIn(t)) {
            return Reminders.spokenList(ctx)
        }
        val cancel = Regex("^(?:cancel|delete|remove|clear|turn off)\\b.*\\b(alarm|alarms|reminder|reminders|timer|timers)\\b").find(t)
        if (cancel != null) {
            if (Regex("\\ball\\b").containsMatchIn(t)) {
                return "To be safe I cancel one at a time. Tell me which one, for example: cancel the 8 AM alarm."
            }
            val kind = when {
                cancel.groupValues[1].startsWith("alarm") -> Kind.ALARM
                cancel.groupValues[1].startsWith("timer") -> Kind.TIMER
                else -> Kind.REMINDER
            }
            return Reminders.cancelMatching(ctx, t, kind)
        }

        // --- timer ---
        if (Regex("\\btimer\\b").containsMatchIn(t)) return setTimer(t)

        // --- alarm, wake-up, reminder ---
        if (Regex("\\b(wake me|wake up call|set (an |a |the |my )?(alarm|reminder)|remind me|call me|alarm for|reminder for|alert me)\\b")
                .containsMatchIn(t)
        ) {
            val r = TimeParser.parse(t)
            if (!r.ok) return r.error
            val out = Reminders.create(ctx, r.title, r.kind, r.whenMs, r.repeat)
            var say = out.spoken
            if (out.reminder != null) {
                if (r.callRequest) {
                    say += " I cannot place a real phone call yet, so it will ring as an alarm on this phone."
                }
                if (r.guessedAmPm) say += " You did not say AM or PM, so I chose. Tell me if that is wrong."
            }
            return say
        }
        return null
    }

    private fun timerName(s: Long): String = when {
        s % 3600L == 0L -> "${s / 3600L} hour timer"
        s % 60L == 0L -> "${s / 60L} minute timer"
        s > 60L -> "${s / 60L} minute ${s % 60L} second timer"
        else -> "$s second timer"
    }

    private fun setTimer(t: String): String {
        val m = Regex("(\\d+)\\s*(second|sec|minute|min|hour)").find(t)
            ?: return "Tell me how long, for example: set a timer for 5 minutes."
        val n = m.groupValues[1].toLong()
        val unit = m.groupValues[2]
        val secs = n * when {
            unit.startsWith("sec") -> 1L
            unit.startsWith("min") -> 60L
            else -> 3600L
        }
        return startTimer(secs, "")
    }

    /**
     * Starts a countdown that rings when it ends. It is saved and scheduled with Android's AlarmManager,
     * so it still rings if Jarvis is stopped or the screen is off. Used by the offline commands and by the AI.
     */
    fun startTimer(seconds: Long, what: String): String {
        val secs = seconds.coerceAtLeast(2L)
        val label = describeDuration(secs)
        val suffix = if (what.isBlank()) "" else " for $what"
        val out = Reminders.create(
            ctx, timerName(secs) + suffix, Kind.TIMER, System.currentTimeMillis() + secs * 1000L, Repeat.NONE
        )
        return if (out.reminder == null) out.spoken else "Timer set for $label$suffix."
    }

    private fun describeDuration(s: Long): String = when {
        s % 3600L == 0L -> "${s / 3600L} " + if (s == 3600L) "hour" else "hours"
        s % 60L == 0L -> "${s / 60L} " + if (s == 60L) "minute" else "minutes"
        s > 60L -> "${s / 60L} minutes ${s % 60L} seconds"
        else -> "$s seconds"
    }

    fun batteryText(): String {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        return "Battery is at ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} percent."
    }

    fun sendWhatsapp(number: String, message: String): String {
        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) return "I need a phone number with country code."
        return launch("https://wa.me/$digits?text=" + enc(message), "WhatsApp")
    }

    /**
     * Android blocks background services from opening apps directly. So we post a
     * high-priority notification: tap it (or it pops up on the lock screen) to open the link.
     */
    fun launch(url: String, label: String): String =
        launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)), label)

    fun launchIntent(intent: Intent, label: String): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            ctx, 100, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, Config.CHANNEL_ACTION)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis")
            .setContentText("Tap to open $label")
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(Config.NOTIF_ACTION, n)
        return "Notification posted. The user must tap it to open $label."
    }

    /** Offline path: same as launch() but with a sentence meant to be read out. */
    private fun spokenLaunch(url: String, label: String): String {
        launch(url, label)
        return "Opening $label. Tap the notification if it does not open."
    }

    /** Timers now live in Android's AlarmManager, so there is nothing to clean up here. */
    fun release() {
        main.removeCallbacksAndMessages(null)
    }

    // ---------- Helpers ----------

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun numberWordsToDigits(s: String): String {
        val words = mapOf(
            "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
            "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
            "fifteen" to "15", "twenty" to "20", "thirty" to "30", "forty five" to "45",
            "sixty" to "60"
        )
        var out = s
        for ((w, d) in words) out = out.replace(Regex("\\b$w\\b"), d)
        return out
    }
}

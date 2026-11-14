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
    private val timers = java.util.concurrent.CopyOnWriteArrayList<Runnable>()
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

        if (t.contains("timer") || t.contains("remind me")) return setTimer(t)

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

    /** Starts a countdown that speaks when it ends. Used by the offline commands and by the AI. */
    fun startTimer(seconds: Long, what: String): String {
        val label = describeDuration(seconds)
        val suffix = if (what.isBlank()) "" else " for $what"
        val r = Runnable { say("Your $label timer$suffix is finished.") }
        timers.add(r)
        main.postDelayed(r, seconds * 1000L)
        return "Timer set for $label$suffix."
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

    /** Prepares an alarm. Android only lets a background service open it through a notification tap. */
    fun setAlarm(hour: Int, minute: Int, label: String): String {
        if (hour !in 0..23 || minute !in 0..59) return "That time is not valid."
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
            .putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
            .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
        val shown = SimpleDateFormat("h:mm a", Locale.getDefault()).format(
            java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, hour)
                set(java.util.Calendar.MINUTE, minute)
            }.time
        )
        return launchIntent(intent, "the $shown alarm")
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

    fun release() {
        timers.forEach { main.removeCallbacks(it) }
        timers.clear()
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

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
    private val timers = mutableListOf<Runnable>()
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
            val bm = ctx.getSystemService(BatteryManager::class.java)
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            return "Battery is at $pct percent."
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
            return launch("https://www.youtube.com/results?search_query=" + enc(q), "YouTube search for $q")
        }
        if (t.contains("open youtube")) return launch("https://www.youtube.com", "YouTube")
        if (t.contains("open whatsapp")) return launch("https://wa.me/", "WhatsApp")
        if (Regex("open (gmail|mail)").containsMatchIn(t)) return launch("https://mail.google.com", "Gmail")
        if (t.contains("open maps")) return launch("https://maps.google.com", "Maps")
        if (t.contains("news")) return launch("https://news.google.com", "the news")
        if (t.contains("weather")) {
            return launch("https://www.google.com/search?q=" + enc(t), "the weather")
        }

        val search = Regex("(?:search|google|look up|find)\\s+(?:for\\s+)?(.+)").find(t)
        if (search != null) {
            val q = search.groupValues[1]
            return launch("https://www.google.com/search?q=" + enc(q), "a Google search for $q")
        }
        if (Regex("^(what|who|when|where|why|how|which)\\b").containsMatchIn(t)) {
            return launch("https://www.google.com/search?q=" + enc(t), "a Google search")
        }

        return "I did not understand that. Try: what time is it, battery level, torch on, set a timer for 5 minutes, or play songs on YouTube."
    }

    // ---------- Actions ----------

    private fun setTorch(on: Boolean): String {
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

    private fun changeVolume(t: String): String {
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
        val ms = n * when {
            unit.startsWith("sec") -> 1000L
            unit.startsWith("min") -> 60_000L
            else -> 3_600_000L
        }
        val label = "$n " + when {
            unit.startsWith("sec") -> "seconds"
            unit.startsWith("min") -> "minutes"
            else -> "hours"
        }
        val r = Runnable { say("Your $label timer is finished.") }
        timers.add(r)
        main.postDelayed(r, ms)
        return "Timer set for $label."
    }

    /**
     * Android blocks background services from opening apps directly. So we post a
     * high-priority notification: tap it (or it pops up on the lock screen) to open the link.
     */
    private fun launch(url: String, label: String): String {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

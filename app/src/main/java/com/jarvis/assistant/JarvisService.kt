package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.File
import java.util.Locale
import org.vosk.android.RecognitionListener as VoskListener

/**
 * Foreground service. Flow:
 *   Vosk (offline, no account) listens for "Jarvis"  ->  beep  ->  Android SpeechRecognizer
 *   hears one command  ->  CommandHandler answers  ->  TextToSpeech speaks  ->  back to Vosk.
 * Only one thing uses the microphone at a time.
 */
class JarvisService : Service() {

    private val tag = "JarvisService"
    private val main = Handler(Looper.getMainLooper())

    // Wake word (Vosk)
    private var model: Model? = null
    private var preparing = false
    private var speech: SpeechService? = null
    private var wakeRecognizer: Recognizer? = null

    // Command listening, speaking
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var busy = false
    private var listening = false
    private val pending = HashMap<String, () -> Unit>()

    private lateinit var commands: CommandHandler
    private lateinit var agent: GeminiAgent
    private var followUps = 0

    // Words the speech model may produce when you say "Jarvis".
    private val wakePattern = Regex("(jarvis|jar vis|jarvas|jervis|jarbis|jarvice)")

    private val listenTimeout = Runnable {
        if (endListening()) finishTurn("I did not hear anything.")
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        commands = CommandHandler(
            this,
            say = { msg -> main.post { speak(msg, null) } },
            quit = { main.post { stopSelf() } }
        )
        agent = GeminiAgent(this, commands)
        initTts()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Config.ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                Config.NOTIF_SERVICE,
                buildNotification("Starting..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            // Happens if the microphone permission is missing, or Android refuses a
            // microphone service started while the app is in the background.
            Log.e(tag, "Could not start foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }
        Config.running = true
        acquireWakeLock()
        if (model == null) prepareModel()
        return START_STICKY
    }

    // ---------- Speech model (loaded once, copied out of the APK on first start) ----------

    private fun prepareModel() {
        if (preparing) return
        preparing = true
        updateNotification("Preparing speech model (first start takes about a minute)...")
        Thread {
            try {
                if (assets.list("model-en-us").isNullOrEmpty()) {
                    main.post {
                        fail("Speech model missing from the app. See the README, Step 'Speech model'.")
                    }
                    return@Thread
                }
                val dir = File(filesDir, "vosk-model")
                val ready = File(dir, ".ready")
                if (!ready.exists()) {
                    dir.deleteRecursively()
                    copyAssetDir("model-en-us", dir)
                    ready.writeText("ok")
                }
                val m = Model(dir.absolutePath)
                main.post {
                    preparing = false
                    if (Config.running) {
                        model = m
                        startWakeListening()
                    } else {
                        m.close()
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Model error", e)
                main.post { fail("Speech model error: ${e.message}") }
            }
        }.start()
    }

    private fun copyAssetDir(src: String, dst: File) {
        val children = assets.list(src) ?: emptyArray()
        if (children.isEmpty()) {
            try {
                dst.parentFile?.mkdirs()
                assets.open(src).use { input -> dst.outputStream().use { out -> input.copyTo(out) } }
            } catch (e: java.io.FileNotFoundException) {
                dst.mkdirs() // it was an empty folder
            }
        } else {
            dst.mkdirs()
            for (name in children) copyAssetDir("$src/$name", File(dst, name))
        }
    }

    // ---------- Wake word ----------

    private fun startWakeListening() {
        val m = model ?: return
        try {
            val rec = Recognizer(m, 16000.0f)
            val svc = SpeechService(rec, 16000.0f)
            wakeRecognizer = rec
            speech = svc
            svc.startListening(object : VoskListener {
                override fun onPartialResult(hypothesis: String?) = checkForWake(hypothesis)
                override fun onResult(hypothesis: String?) = checkForWake(hypothesis)
                override fun onFinalResult(hypothesis: String?) = checkForWake(hypothesis)
                override fun onError(exception: Exception?) {
                    Log.e(tag, "Vosk error", exception)
                }

                override fun onTimeout() {}
            })
            updateNotification("Say \"Jarvis\" or \"Hey Jarvis\"")
        } catch (e: Exception) {
            Log.e(tag, "Could not start wake listening", e)
            fail("Wake word error: ${e.message}")
        }
    }

    private fun checkForWake(json: String?) {
        if (busy || json == null) return
        val text = try {
            val o = JSONObject(json)
            o.optString("partial", o.optString("text", ""))
        } catch (e: Exception) {
            ""
        }
        if (wakePattern.containsMatchIn(text.lowercase(Locale.ROOT))) onWake()
    }

    private fun stopWakeListening() {
        val s = speech
        val r = wakeRecognizer
        speech = null
        wakeRecognizer = null
        try {
            s?.stop()
            s?.shutdown() // releases the microphone
        } catch (e: Exception) {
            Log.w(tag, "stop wake", e)
        }
        try {
            r?.close()
        } catch (e: Exception) {
            Log.w(tag, "close recognizer", e)
        }
    }

    private fun onWake() {
        if (busy) return
        busy = true
        followUps = 0
        main.post {
            stopWakeListening()
            updateNotification("Listening...")
            try {
                ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                    .startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            } catch (_: Exception) {
            }
            main.postDelayed({ listen() }, 350)
        }
    }

    // ---------- Command listening ----------

    private fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            finishTurn("Speech recognition is not available. Please enable the Google app.")
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        listening = true
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                if (!endListening()) return
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (text.isNullOrBlank()) {
                    finishTurn("I did not catch that.")
                } else {
                    think(text)
                }
            }

            override fun onError(error: Int) {
                if (!endListening()) return
                finishTurn(
                    if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ) "I did not catch that." else "Speech error $error."
                )
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        r.startListening(intent)
        main.postDelayed(listenTimeout, 12000)
    }

    /** Returns true only the first time it is called for a listening turn. */
    private fun endListening(): Boolean {
        if (!listening) return false
        listening = false
        main.removeCallbacks(listenTimeout)
        val r = recognizer
        recognizer = null
        if (r != null) main.post { r.destroy() }
        return true
    }

    /**
     * Sends the command to the Gemini agent (background thread). If there is no key, or no internet,
     * the old offline command list answers instead.
     */
    private fun think(text: String) {
        if (!agent.isConfigured()) {
            finishTurn(commands.handle(text))
            return
        }
        updateNotification("Thinking...")
        Thread {
            var reply: String? = null
            var offline = false
            try {
                reply = agent.ask(text)
            } catch (e: GeminiAgent.AgentException) {
                if (e.offline) offline = true else reply = e.spoken
            } catch (e: Exception) {
                Log.e(tag, "Agent error", e)
                reply = "Something went wrong."
            }
            main.post {
                if (!Config.running) return@post
                when {
                    offline -> finishTurn(commands.handle(text))
                    else -> finishTurn(reply ?: "", followUp = true)
                }
            }
        }.start()
    }

    /** followUp: if the reply is a question, listen again right away without needing the wake word. */
    private fun finishTurn(reply: String, followUp: Boolean = false) {
        if (reply.isBlank()) {
            resumeWakeWord()
        } else {
            updateNotification("Speaking...")
            val again = followUp && reply.trimEnd().endsWith("?") && followUps < 2
            speak(reply) {
                if (again && Config.running) {
                    followUps++
                    updateNotification("Listening...")
                    listen()
                } else {
                    resumeWakeWord()
                }
            }
        }
    }

    private fun resumeWakeWord() {
        busy = false
        if (Config.running) startWakeListening()
    }

    // ---------- Speech output ----------

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts
                if (t != null) {
                    val res = t.setLanguage(Locale("en", "IN"))
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        t.setLanguage(Locale.US)
                    }
                    t.setPitch(0.9f)
                    t.setSpeechRate(0.98f)
                    ttsReady = true
                }
            } else {
                Log.e(tag, "TextToSpeech init failed: $status")
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                main.post { utteranceId?.let { pending.remove(it)?.invoke() } }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { utteranceId?.let { pending.remove(it)?.invoke() } }
            }
        })
    }

    private fun speak(text: String, onDone: (() -> Unit)?) {
        val t = tts
        if (t == null || !ttsReady) {
            onDone?.invoke()
            return
        }
        val id = "u" + System.nanoTime()
        if (onDone != null) pending[id] = onDone
        t.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    // ---------- Notifications, wake lock, cleanup ----------

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(Config.CHANNEL_SERVICE, "Jarvis running", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(Config.CHANNEL_ACTION, "Jarvis actions", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, JarvisService::class.java).setAction(Config.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, Config.CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(Config.NOTIF_SERVICE, buildNotification(text))
    }

    private fun fail(message: String) {
        updateNotification(message)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::WakeWord").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onDestroy() {
        Config.running = false
        main.removeCallbacksAndMessages(null)
        stopWakeListening()
        try {
            model?.close()
        } catch (e: Exception) {
            Log.w(tag, "model close", e)
        }
        model = null
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        commands.release()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }
}

package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var keyBox: EditText
    private lateinit var modelBox: EditText
    private val main = Handler(Looper.getMainLooper())

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasMic()) {
                startJarvis()
            } else {
                status.text = "Microphone permission is required for the wake word."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------- Actions ----------

    private fun prefs() = getSharedPreferences(Config.PREFS, MODE_PRIVATE)

    private fun onSaveKeyClicked() {
        val key = keyBox.text.toString().trim()
        val model = modelBox.text.toString().trim().ifEmpty { Config.DEFAULT_MODEL }
        prefs().edit().putString(Config.KEY_GEMINI, key).putString(Config.KEY_MODEL, model).apply()
        Toast.makeText(
            this,
            if (key.isEmpty()) "Key cleared. Jarvis will use the offline commands." else "Saved. Jarvis will now use Gemini.",
            Toast.LENGTH_LONG
        ).show()
        refreshStatus()
    }

    private fun onStartClicked() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startJarvis() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startJarvis() {
        // Started while the app is on screen, which Android requires for a microphone service.
        ContextCompat.startForegroundService(this, Intent(this, JarvisService::class.java))
        status.text = "Starting..."
        main.postDelayed({ refreshStatus() }, 1500)
    }

    private fun onStopClicked() {
        stopService(Intent(this, JarvisService::class.java))
        main.postDelayed({ refreshStatus() }, 500)
    }

    private fun onBatteryClicked() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already allowed. Also check the Vivo settings in the README.", Toast.LENGTH_LONG).show()
        } else {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun onAppSettingsClicked() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun refreshStatus() {
        val brain = if (prefs().getString(Config.KEY_GEMINI, "").isNullOrBlank())
            "Brain: offline commands only (no Gemini key saved)."
        else "Brain: Gemini AI."
        status.text = (if (Config.running) {
            "Jarvis is running. Lock the screen and say the wake word."
        } else {
            "Jarvis is stopped."
        }) + "\n" + brain
    }

    // ---------- UI (built in code so the project needs no layout files) ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
            setBackgroundColor(Color.parseColor("#030B14"))
        }
        root.addView(TextView(this).apply {
            text = "JARVIS"
            textSize = 34f
            letterSpacing = 0.3f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#00E5FF"))
        })
        root.addView(TextView(this).apply {
            text = "AI voice assistant"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#88AABB"))
            setPadding(0, 0, 0, dp(24))
        })

        root.addView(TextView(this).apply {
            text = "Gemini API key (free at aistudio.google.com/apikey)"
            setTextColor(Color.parseColor("#88AABB"))
        })
        keyBox = EditText(this).apply {
            hint = "Paste your key here"
            setText(prefs().getString(Config.KEY_GEMINI, ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#557788"))
        }
        root.addView(keyBox)
        root.addView(TextView(this).apply {
            text = "Model name"
            setTextColor(Color.parseColor("#88AABB"))
            setPadding(0, dp(8), 0, 0)
        })
        modelBox = EditText(this).apply {
            setText(prefs().getString(Config.KEY_MODEL, Config.DEFAULT_MODEL))
            setSingleLine()
            setTextColor(Color.WHITE)
        }
        root.addView(modelBox)
        root.addView(button("Save key") { onSaveKeyClicked() })

        root.addView(button("Start Jarvis") { onStartClicked() })
        root.addView(button("Stop Jarvis") { onStopClicked() })
        root.addView(button("Allow background battery use") { onBatteryClicked() })
        root.addView(button("Open app settings (Vivo battery and autostart)") { onAppSettingsClicked() })

        status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#00E5FF"))
            setPadding(0, dp(24), 0, 0)
        }
        root.addView(status)

        setContentView(ScrollView(this).apply { addView(root) })
    }
}

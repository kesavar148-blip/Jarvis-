package com.jarvis.assistant

object Config {
    const val PREFS = "jarvis_prefs"
    const val KEY_ACCESS = "access_key"
    const val KEY_GEMINI = "gemini_key"
    const val KEY_MODEL = "gemini_model"
    // Free-tier model per Google's pricing page. You can change it in the app if Google renames it.
    const val DEFAULT_MODEL = "gemini-3.1-flash-lite"

    const val CHANNEL_SERVICE = "jarvis_service"
    const val CHANNEL_ACTION = "jarvis_action"
    const val NOTIF_SERVICE = 1
    const val NOTIF_ACTION = 2

    const val ACTION_STOP = "com.jarvis.assistant.STOP"

    // Process-local flag so the screen can show whether the service is alive.
    @Volatile
    var running = false
}

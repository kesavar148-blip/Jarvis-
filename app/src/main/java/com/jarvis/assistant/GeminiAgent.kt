package com.jarvis.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The AI brain. Sends what you said to Google's Gemini API, lets Gemini choose phone actions
 * (tools), runs them through [CommandHandler], and returns one short sentence to speak.
 *
 * Uses plain HttpURLConnection and org.json (both built into Android), so no extra libraries.
 */
class GeminiAgent(
    private val ctx: Context,
    private val tools: CommandHandler
) {
    class AgentException(val spoken: String, val offline: Boolean = false) : Exception(spoken)

    // Short memory of earlier turns (plain text only), so "and tomorrow?" works.
    private val history = ArrayList<Pair<String, String>>()
    private val maxHistory = 6

    fun apiKey(): String =
        ctx.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE).getString(Config.KEY_GEMINI, "")?.trim() ?: ""

    private fun model(): String =
        ctx.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)
            .getString(Config.KEY_MODEL, Config.DEFAULT_MODEL)?.trim()
            .let { if (it.isNullOrEmpty()) Config.DEFAULT_MODEL else it }

    fun isConfigured() = apiKey().isNotEmpty()

    /** Blocking. Call from a background thread. Throws AgentException with a sentence to speak. */
    fun ask(userText: String): String {
        val key = apiKey()
        if (key.isEmpty()) throw AgentException("No Gemini key is set. Open the Jarvis app and paste your key.")

        // Full contents for this turn: earlier plain-text turns + the new question.
        val contents = JSONArray()
        for ((u, a) in history) {
            contents.put(content("user", textPart(u)))
            contents.put(content("model", textPart(a)))
        }
        contents.put(content("user", textPart(userText)))

        var finalText = ""
        // Up to 5 rounds: model asks for tools -> we run them -> model answers.
        for (round in 0 until 5) {
            val response = call(key, contents)
            val candidate = response.optJSONArray("candidates")?.optJSONObject(0)
                ?: throw AgentException("I got no answer from Gemini.")
            val modelContent = candidate.optJSONObject("content")
            val parts = modelContent?.optJSONArray("parts") ?: JSONArray()

            val calls = ArrayList<JSONObject>()
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                val fc = p.optJSONObject("functionCall")
                if (fc != null) calls.add(fc)
                else if (p.has("text") && !p.optBoolean("thought", false)) sb.append(p.getString("text"))
            }

            if (calls.isEmpty()) {
                finalText = sb.toString().trim()
                break
            }

            // Keep the model's turn exactly as received (it may carry thought signatures).
            contents.put(modelContent)
            val results = JSONArray()
            for (fc in calls) {
                val name = fc.optString("name")
                val args = fc.optJSONObject("args") ?: JSONObject()
                val result = try {
                    runTool(name, args)
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
                val fr = JSONObject()
                    .put("name", name)
                    .put("response", JSONObject().put("result", result))
                if (fc.has("id")) fr.put("id", fc.getString("id"))
                results.put(JSONObject().put("functionResponse", fr))
            }
            contents.put(JSONObject().put("role", "user").put("parts", results))
        }

        if (finalText.isEmpty()) finalText = "Done."
        finalText = cleanForSpeech(finalText)

        history.add(userText to finalText)
        while (history.size > maxHistory) history.removeAt(0)
        return finalText
    }

    // ---------- Tools Gemini may call ----------

    private fun runTool(name: String, a: JSONObject): String = when (name) {
        "set_flashlight" -> tools.setTorch(a.optBoolean("on", true))
        "change_volume" -> tools.changeVolume(a.optString("action", ""))
        "set_timer" -> tools.startTimer(
            a.optLong("seconds", 0L).coerceIn(1L, 86_400L),
            a.optString("label", "")
        )
        "get_battery" -> tools.batteryText()
        "open_link" -> tools.launch(a.optString("url", ""), a.optString("label", "the page"))
        "search_web" -> tools.launch(
            "https://www.google.com/search?q=" + java.net.URLEncoder.encode(a.optString("query", ""), "UTF-8"),
            "a Google search"
        )
        "play_youtube" -> tools.launch(
            "https://www.youtube.com/results?search_query=" +
                java.net.URLEncoder.encode(a.optString("query", ""), "UTF-8"),
            "YouTube search for " + a.optString("query", "")
        )
        "navigate_to" -> tools.launch(
            "https://www.google.com/maps/dir/?api=1&destination=" +
                java.net.URLEncoder.encode(a.optString("place", ""), "UTF-8"),
            "directions to " + a.optString("place", "")
        )
        "call_number" -> tools.launch("tel:" + a.optString("number", "").filter { it.isDigit() || it == '+' }, "the dialer")
        "send_whatsapp" -> tools.sendWhatsapp(a.optString("number", ""), a.optString("message", ""))
        "set_alarm" -> tools.setAlarm(a.optInt("hour", 0), a.optInt("minute", 0), a.optString("label", ""))
        else -> "Unknown tool $name"
    }

    private fun toolDeclarations(): JSONArray {
        fun fn(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
            JSONObject()
                .put("name", name)
                .put("description", desc)
                .apply {
                    if (props.length() > 0) {
                        put(
                            "parameters", JSONObject()
                                .put("type", "object")
                                .put("properties", props)
                                .put("required", JSONArray(required))
                        )
                    }
                }

        fun str(d: String) = JSONObject().put("type", "string").put("description", d)
        fun int(d: String) = JSONObject().put("type", "integer").put("description", d)
        fun bool(d: String) = JSONObject().put("type", "boolean").put("description", d)

        val decls = JSONArray()
        decls.put(fn("set_flashlight", "Turn the phone flashlight on or off.",
            JSONObject().put("on", bool("true to turn on, false to turn off")), listOf("on")))
        decls.put(fn("change_volume", "Change the media volume.",
            JSONObject().put("action", str("one of: up, down, max, mute")), listOf("action")))
        decls.put(fn("set_timer", "Start a countdown timer that speaks when finished. Also use for 'remind me in N minutes'.",
            JSONObject().put("seconds", int("Total seconds, e.g. 300 for 5 minutes"))
                .put("label", str("What the timer is for, may be empty")), listOf("seconds")))
        decls.put(fn("get_battery", "Get the phone battery percentage."))
        decls.put(fn("open_link", "Open a web page or app link such as https://mail.google.com or https://wa.me/.",
            JSONObject().put("url", str("Full URL starting with https://"))
                .put("label", str("Short spoken name of what is being opened")), listOf("url", "label")))
        decls.put(fn("search_web", "Open a Google search for the user. Use for current news, weather, prices, scores and any fact you cannot know.",
            JSONObject().put("query", str("Search text")), listOf("query")))
        decls.put(fn("play_youtube", "Search YouTube for songs, stories or videos.",
            JSONObject().put("query", str("What to play")), listOf("query")))
        decls.put(fn("navigate_to", "Open Google Maps directions to a place.",
            JSONObject().put("place", str("Place name or address")), listOf("place")))
        decls.put(fn("call_number", "Open the phone dialer with a number ready. The user presses call.",
            JSONObject().put("number", str("Phone number digits, with country code if known")), listOf("number")))
        decls.put(fn("send_whatsapp", "Open WhatsApp with a message ready for a phone number. The user presses send.",
            JSONObject().put("number", str("Phone number with country code, digits only"))
                .put("message", str("Message text")), listOf("number", "message")))
        decls.put(fn("set_alarm", "Set a clock alarm at a time of day (24-hour).",
            JSONObject().put("hour", int("Hour 0 to 23")).put("minute", int("Minute 0 to 59"))
                .put("label", str("Alarm label, may be empty")), listOf("hour", "minute")))
        return JSONArray().put(JSONObject().put("functionDeclarations", decls))
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.getDefault()).format(Date())
        return "You are Jarvis, a friendly voice assistant running on the user's Android phone. " +
            "Everything you write is spoken aloud, so: answer in one or two short sentences, plain words, " +
            "no markdown, no lists, no emojis. Only tell a longer story or explanation if asked. " +
            "Use the tools to control the phone when the user wants an action; do not say you did " +
            "something unless a tool confirmed it. For live facts you cannot know (weather, news, prices, scores) " +
            "use search_web. If a request is unclear, ask one short question. " +
            "Reply in the language the user spoke. Current local time: $now."
    }

    // ---------- HTTP ----------

    private fun call(key: String, contents: JSONArray): JSONObject {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", contents)
            .put("tools", toolDeclarations())
            .put("generationConfig", JSONObject().put("maxOutputTokens", 1500))

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${model()}:generateContent")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 40_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw AgentException(explain(code, text))
            return JSONObject(text)
        } catch (e: AgentException) {
            throw e
        } catch (e: java.io.IOException) {
            throw AgentException("I cannot reach the internet.", true)
        } finally {
            conn.disconnect()
        }
    }

    private fun explain(code: Int, body: String): String = when (code) {
        400 -> if (body.contains("API key", ignoreCase = true)) "The Gemini key is not valid." else "Gemini did not accept that request."
        401, 403 -> "The Gemini key is not valid or not allowed."
        404 -> "The model name is wrong. Check it in the Jarvis app."
        429 -> "I have used up the free Gemini limit for now. Try again in a minute."
        in 500..599 -> "Gemini is busy. Try again."
        else -> "Gemini error $code."
    }

    // ---------- Helpers ----------

    private fun textPart(t: String) = JSONObject().put("text", t)

    private fun content(role: String, part: JSONObject) =
        JSONObject().put("role", role).put("parts", JSONArray().put(part))

    /** Removes characters that sound odd when read aloud. */
    private fun cleanForSpeech(s: String): String =
        s.replace(Regex("[*_`#>]+"), "").replace(Regex("\\s+"), " ").trim()
}

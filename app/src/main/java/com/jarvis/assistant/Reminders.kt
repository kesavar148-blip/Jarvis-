package com.jarvis.assistant

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** One place for creating, listing and cancelling alarms, reminders, timers and tasks. Used by voice commands, the AI and the screens. */
object Reminders {

    class Outcome(val reminder: Reminder?, val spoken: String)

    private val STOP = setOf(
        "the", "my", "alarm", "alarms", "reminder", "reminders", "cancel", "delete", "remove", "turn", "off",
        "please", "for", "and", "task", "tasks", "done", "complete", "finish", "mark", "all"
    )

    fun defaultTitle(kind: Kind): String = when (kind) {
        Kind.ALARM -> "Alarm"
        Kind.REMINDER -> "Reminder"
        Kind.TIMER -> "Timer"
    }

    fun label(kind: Kind): String = when (kind) {
        Kind.ALARM -> "alarm"
        Kind.REMINDER -> "reminder"
        Kind.TIMER -> "timer"
    }

    /** Saves it, hands it to Android's alarm manager and returns the sentence to speak back. */
    fun create(ctx: Context, title: String, kind: Kind, atMs: Long, repeat: Repeat): Outcome {
        if (atMs <= System.currentTimeMillis() + 1_000L) return Outcome(null, "That time has already passed.")
        val saved = ReminderDb.of(ctx).insert(
            Reminder(0L, title.trim(), kind, atMs, atMs, repeat, Status.PENDING, 0)
        )
        AlarmScheduler.schedule(ctx, saved)
        return Outcome(saved, confirmation(saved))
    }

    fun confirmation(r: Reminder): String {
        val whenText = TimeFmt.humanWhen(r.triggerAt)
        val rep = if (r.repeat == Repeat.NONE) "" else ", repeating " + TimeFmt.repeatText(r.repeat)
        val named = r.title.isNotBlank() && r.title != defaultTitle(r.kind)
        return when (r.kind) {
            Kind.ALARM ->
                "Your alarm" + (if (named) " for " + r.title else "") + " has been scheduled for " + whenText + rep + "."
            Kind.REMINDER ->
                "Your reminder" + (if (named) " to " + r.title else "") + " has been scheduled for " + whenText + rep + "."
            Kind.TIMER -> "Your " + r.title + " will finish " + whenText + "."
        }
    }

    fun describe(r: Reminder): String {
        val name = if (r.title.isBlank()) defaultTitle(r.kind) else r.title
        val rep = if (r.repeat == Repeat.NONE) "" else ", " + TimeFmt.repeatText(r.repeat)
        return name + ", " + TimeFmt.humanWhen(r.triggerAt) + rep
    }

    /** Alarms and reminders (timers are short-lived and left out). */
    fun spokenList(ctx: Context): String {
        val list = ReminderDb.of(ctx).active().filter { it.kind != Kind.TIMER }
        if (list.isEmpty()) return "You have no alarms or reminders scheduled."
        val first = list.take(5).joinToString(". ") { describe(it) }
        return "You have " + list.size + " scheduled. " + first + "." +
            (if (list.size > 5) " The rest are in the app." else "")
    }

    fun cancel(ctx: Context, id: Long) {
        AlarmScheduler.cancel(ctx, id)
        ReminderDb.of(ctx).delete(id)
    }

    /**
     * Cancels the one alarm or reminder that matches [text] (words from the title, or a time such as "8 AM").
     * It never cancels more than one at a time: if it is not clear which, it says what you have.
     */
    fun cancelMatching(ctx: Context, text: String, kind: Kind?): String {
        val all = ReminderDb.of(ctx).active().filter { kind == null || it.kind == kind }
        val noun = if (kind == null) "alarms or reminders" else label(kind) + "s"
        if (all.isEmpty()) return "You have no $noun scheduled."

        val words = text.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 && it !in STOP && !it.all { ch -> ch.isDigit() } }
        val parsed = TimeParser.parse(text)
        val clock: Long? = if (parsed.ok) parsed.whenMs else null

        val matches = all.filter { r ->
            val byWord = words.any { w -> r.title.lowercase(Locale.ROOT).contains(w) }
            val byClock = clock != null && sameClock(r.baseAt, clock)
            byWord || byClock
        }
        val chosen = when {
            matches.size == 1 -> matches
            words.isEmpty() && clock == null && all.size == 1 -> all
            else -> emptyList()
        }
        if (chosen.size == 1) {
            val r = chosen[0]
            cancel(ctx, r.id)
            return "Cancelled " + describe(r) + "."
        }
        val names = all.take(3).joinToString(". ") { describe(it) }
        return if (matches.isEmpty() && (words.isNotEmpty() || clock != null)) {
            "I could not find a $noun like that. You have: $names."
        } else {
            "You have " + all.size + " " + noun + ": " + names + ". Tell me which one, for example the time."
        }
    }

    private fun sameClock(a: Long, b: Long): Boolean {
        val x = Calendar.getInstance()
        x.timeInMillis = a
        val y = Calendar.getInstance()
        y.timeInMillis = b
        return x.get(Calendar.HOUR) == y.get(Calendar.HOUR) && x.get(Calendar.MINUTE) == y.get(Calendar.MINUTE)
    }

    /** The AI sends local times like 2026-11-18T08:00. Returns null if it cannot be read. */
    fun parseLocal(iso: String): Long? {
        for (pattern in arrayOf("yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss")) {
            try {
                val f = SimpleDateFormat(pattern, Locale.US)
                f.isLenient = false
                val d = f.parse(iso.trim())
                if (d != null) return d.time
            } catch (e: Exception) {
                // try the next pattern
            }
        }
        return null
    }

    // ---------- To-do list ----------

    fun addTask(ctx: Context, title: String): String {
        val t = title.trim()
        if (t.isEmpty()) return "What is the task?"
        ReminderDb.of(ctx).addTask(t)
        return "Added to your tasks: $t."
    }

    fun spokenTasks(ctx: Context): String {
        val list = ReminderDb.of(ctx).pendingTasks()
        if (list.isEmpty()) return "You have no pending tasks."
        val names = list.take(8).joinToString(", ") { it.title }
        return "You have " + list.size + " pending " + (if (list.size == 1) "task" else "tasks") + ": " + names + "." +
            (if (list.size > 8) " The rest are in the app." else "")
    }

    fun completeTask(ctx: Context, text: String): String {
        val db = ReminderDb.of(ctx)
        val list = db.pendingTasks()
        if (list.isEmpty()) return "You have no pending tasks."
        val words = text.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in STOP }
        val matches = list.filter { t -> words.any { w -> t.title.lowercase(Locale.ROOT).contains(w) } }
        val chosen = when {
            matches.size == 1 -> matches[0]
            words.isEmpty() && list.size == 1 -> list[0]
            else -> null
        }
        if (chosen == null) {
            return "Which task? You have: " + list.take(5).joinToString(", ") { it.title } + "."
        }
        db.completeTask(chosen.id)
        return "Marked done: " + chosen.title + "."
    }
}

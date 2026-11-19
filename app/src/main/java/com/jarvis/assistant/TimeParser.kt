package com.jarvis.assistant

import java.util.Calendar
import java.util.Locale

/**
 * Understands phrases such as "wake me up tomorrow at 8 AM", "remind me to study at 6 PM",
 * "wake me up at 5 AM every day" or "remind me in 30 minutes to call mom".
 * Pure Kotlin (no Android classes) so it can be tested on any computer.
 */
object TimeParser {

    class Result(
        val whenMs: Long = 0L,
        val repeat: Repeat = Repeat.NONE,
        val title: String = "",
        val kind: Kind = Kind.REMINDER,
        /** True when the user did not say AM or PM and we had to choose. Jarvis speaks the time back. */
        val guessedAmPm: Boolean = false,
        /** True for "call me ...". Jarvis can only ring an alarm, and must say so. */
        val callRequest: Boolean = false,
        /** A sentence to speak when the request could not be understood. */
        val error: String? = null
    ) {
        val ok: Boolean get() = error == null
    }

    private val DAY_NAMES = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
    private val DAY_ALT = DAY_NAMES.joinToString("|")
    private val NUM_WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12
    )

    fun parse(input: String, now: Long = System.currentTimeMillis()): Result {
        var s = " " + normalize(input) + " "

        // Finds the first match, removes it from s (what is left becomes the title) and returns it.
        fun take(re: Regex): MatchResult? {
            val m = re.find(s) ?: return null
            s = s.substring(0, m.range.first) + " " + s.substring(m.range.last + 1)
            return m
        }

        val callRequest = Regex("\\bcall me\\b").containsMatchIn(s)
        val wake = Regex("\\bwake\\b").containsMatchIn(s)
        val kind = if (Regex("\\b(wake|alarm|call me)\\b").containsMatchIn(s)) Kind.ALARM else Kind.REMINDER

        // ---- repeat: every day / every weekday / every monday ----
        var repeat = Repeat.NONE
        var repeatDow = -1
        var dayPart = ""
        val every = take(Regex("\\bevery (day|weekdays?|morning|afternoon|evening|night|$DAY_ALT)\\b"))
        if (every != null) {
            val w = every.groupValues[1]
            when {
                w.startsWith("weekday") -> repeat = Repeat.WEEKDAYS
                w in DAY_NAMES -> {
                    repeat = Repeat.WEEKLY
                    repeatDow = DAY_NAMES.indexOf(w) + 1
                }
                else -> {
                    repeat = Repeat.DAILY
                    if (w != "day") dayPart = w
                }
            }
        } else if (take(Regex("\\b(daily|everyday|each day)\\b")) != null) {
            repeat = Repeat.DAILY
        } else if (take(Regex("\\b(?:on )?weekdays\\b")) != null) {
            repeat = Repeat.WEEKDAYS
        }

        // ---- "in 30 minutes", "in an hour", "in half an hour" ----
        val rel = take(
            Regex("\\bin (?:(\\d+(?:\\.\\d+)?)|(half an?)|(an?)) ?(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?)\\b")
        )
        if (rel != null) {
            val qty = when {
                rel.groupValues[1].isNotEmpty() -> rel.groupValues[1].toDouble()
                rel.groupValues[2].isNotEmpty() -> 0.5
                else -> 1.0
            }
            val unit = rel.groupValues[4]
            val unitMs = when {
                unit.startsWith("sec") -> 1_000.0
                unit.startsWith("min") -> 60_000.0
                unit.startsWith("h") -> 3_600_000.0
                else -> 86_400_000.0
            }
            return Result(now + (qty * unitMs).toLong(), repeat, cleanTitle(s), kind, false, callRequest)
        }

        // ---- which day ----
        var dayOffset = -1
        var nextFlag = false
        var targetDow = -1
        if (take(Regex("\\bday after tomorrow\\b")) != null) {
            dayOffset = 2
        } else if (take(Regex("\\btomorrow\\b")) != null) {
            dayOffset = 1
        } else if (take(Regex("\\btonight\\b")) != null) {
            dayOffset = 0
            if (dayPart.isEmpty()) dayPart = "night"
        } else if (take(Regex("\\btoday\\b")) != null) {
            dayOffset = 0
        }
        if (dayOffset < 0 && repeatDow < 0) {
            val d = take(Regex("\\b(?:(next|this|on) )?($DAY_ALT)\\b"))
            if (d != null) {
                nextFlag = d.groupValues[1] == "next"
                targetDow = DAY_NAMES.indexOf(d.groupValues[2]) + 1
            }
        }

        // ---- part of the day ----
        val part = take(Regex("\\b(?:in the |this |at )?(morning|afternoon|evening|night)\\b"))
        if (part != null && dayPart.isEmpty()) dayPart = part.groupValues[1]

        // ---- clock time ----
        var hour = -1
        var minute = 0
        var marker = ""
        var h24 = -1
        val t1 = Regex("\\b(\\d{1,2}):(\\d{2}) ?(am|pm)?\\b").find(s)
        val t2 = Regex("\\b(\\d{1,2}) ?(am|pm)\\b").find(s)
        val t3 = Regex("\\b(\\d{1,2}) o'?clock\\b").find(s)
        val t4 = Regex(
            "\\b(?:at|by|for|around) (\\d{1,2})\\b(?! ?(?:minutes?|mins?|hours?|hrs?|seconds?|secs?|days?|people|times|more|of))"
        ).find(s)
        val t5 = Regex("\\b(noon|midday|midnight)\\b").find(s)
        val tm: MatchResult? = t1 ?: t2 ?: t3 ?: t4 ?: t5
        when {
            t1 != null -> {
                hour = t1.groupValues[1].toInt()
                minute = t1.groupValues[2].toInt()
                marker = t1.groupValues[3]
            }
            t2 != null -> {
                hour = t2.groupValues[1].toInt()
                marker = t2.groupValues[2]
            }
            t3 != null -> hour = t3.groupValues[1].toInt()
            t4 != null -> hour = t4.groupValues[1].toInt()
            t5 != null -> h24 = if (t5.groupValues[1] == "midnight") 0 else 12
        }
        if (tm != null) s = s.substring(0, tm.range.first) + " " + s.substring(tm.range.last + 1)

        if (tm != null && h24 < 0) {
            val hourOk = if (marker.isNotEmpty()) hour in 1..12 else hour in 0..23
            if (!hourOk || minute !in 0..59) {
                return Result(error = "That time does not look right. Please say it again.")
            }
        }

        var guessed = false
        if (tm == null) {
            if (dayPart.isEmpty()) {
                return Result(error = "What time should I set it for? For example: remind me to study at 6 PM.")
            }
            // Only "tomorrow morning", "every evening" ... Pick a sensible default and say it aloud.
            h24 = when (dayPart) {
                "morning" -> 8
                "afternoon" -> 15
                "evening" -> 18
                else -> 21
            }
        } else if (h24 < 0) {
            h24 = when {
                marker == "am" -> hour % 12
                marker == "pm" -> hour % 12 + 12
                hour == 0 || hour >= 13 -> hour
                dayPart == "morning" || wake -> hour % 12
                dayPart == "night" -> if (hour == 12) 0 else if (hour <= 5) hour else hour % 12 + 12
                dayPart.isNotEmpty() -> hour % 12 + 12
                else -> -1
            }
            if (h24 < 0) {
                // No AM/PM and no hint. Choose, and let the caller speak the result so it can be corrected.
                guessed = true
                val am = hour % 12
                val pm = am + 12
                val explicitDay = dayOffset >= 0 || targetDow > 0 || repeatDow > 0 || repeat != Repeat.NONE
                h24 = if (explicitDay) {
                    // Someone saying "tomorrow at 9" means morning; "at 6" means evening.
                    if (hour in 7..11) am else if (hour == 12) 12 else pm
                } else {
                    // No day given: the soonest time that is still ahead of us.
                    if (todayAt(now, am, minute) > now) am else if (todayAt(now, pm, minute) > now) pm else am
                }
            }
        }

        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, h24)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val dow = if (targetDow > 0) targetDow else repeatDow
        if (dow > 0) {
            var guard = 0
            while ((cal.get(Calendar.DAY_OF_WEEK) != dow || cal.timeInMillis <= now ||
                    (nextFlag && sameDay(cal.timeInMillis, now))) && guard++ < 14
            ) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        } else if (dayOffset >= 0) {
            cal.add(Calendar.DAY_OF_YEAR, dayOffset)
            if (cal.timeInMillis <= now) return Result(error = "That time has already passed today.")
        } else if (cal.timeInMillis <= now) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        if (repeat == Repeat.WEEKDAYS) {
            while (Schedule.isWeekend(cal)) cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        return Result(cal.timeInMillis, repeat, cleanTitle(s), kind, guessed, callRequest)
    }

    /** The next time the clock shows hour:minute (today if still ahead, else tomorrow). */
    fun nextAt(hour: Int, minute: Int, now: Long = System.currentTimeMillis(), weekdaysOnly: Boolean = false): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        if (weekdaysOnly) while (Schedule.isWeekend(cal)) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    // ---------- helpers ----------

    private fun normalize(input: String): String {
        var s = input.lowercase(Locale.ROOT)
        s = s.replace("a.m.", "am").replace("p.m.", "pm")
        s = s.replace(Regex("(\\d)\\.(\\d{2})\\b"), "\$1:\$2")
        s = s.replace(Regex("[,;!?.]"), " ")
        val words = NUM_WORDS.keys.joinToString("|")
        s = s.replace(Regex("\\b(at|by|for|around) ($words)\\b")) { m ->
            m.groupValues[1] + " " + NUM_WORDS[m.groupValues[2]]
        }
        s = s.replace(Regex("\\b($words) (am|pm|o'?clock)\\b")) { m ->
            NUM_WORDS[m.groupValues[1]].toString() + " " + m.groupValues[2]
        }
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /** What is left of the sentence once the time words are removed: "remind me to study" -> "study". */
    private fun cleanTitle(raw: String): String {
        var t = raw.replace(Regex("\\s+"), " ").trim()
        var prev: String
        do {
            prev = t
            t = t.replace(Regex("^(?:please|hey|jarvis|can you|could you|would you|will you|kindly)\\b ?"), "")
            t = t.replace(
                Regex("^(?:remind me|wake me up|wake me|wake up|set (?:an |a |the |my )?(?:alarm|reminder)|call me|alert me|alarm|reminder)\\b ?"),
                ""
            )
            t = t.replace(Regex("^(?:to|about|that|for|of|on|at|in|by|around)\\b ?"), "")
            t = t.replace(Regex(" ?\\b(?:to|about|that|for|of|on|at|in|by|around|the)$"), "")
            t = t.trim()
        } while (t != prev)
        return t
    }

    private fun todayAt(now: Long, hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val x = Calendar.getInstance()
        x.timeInMillis = a
        val y = Calendar.getInstance()
        y.timeInMillis = b
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) &&
            x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
    }
}

/** Repeat rules. Used when an alarm finishes ringing and when the phone restarts. */
object Schedule {
    fun isWeekend(c: Calendar): Boolean {
        val d = c.get(Calendar.DAY_OF_WEEK)
        return d == Calendar.SATURDAY || d == Calendar.SUNDAY
    }

    /** A "weekdays" reminder asked for on a Saturday or Sunday first rings on Monday. */
    fun firstOccurrence(at: Long, repeat: Repeat): Long {
        if (repeat != Repeat.WEEKDAYS) return at
        val cal = Calendar.getInstance()
        cal.timeInMillis = at
        while (isWeekend(cal)) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    /** The first occurrence of a repeating reminder that is later than [now]. Null if it does not repeat. */
    fun nextAfter(base: Long, repeat: Repeat, now: Long): Long? {
        if (repeat == Repeat.NONE) return null
        val cal = Calendar.getInstance()
        cal.timeInMillis = base
        var guard = 0
        while (cal.timeInMillis <= now && guard++ < 5000) {
            when (repeat) {
                Repeat.DAILY -> cal.add(Calendar.DAY_OF_YEAR, 1)
                Repeat.WEEKLY -> cal.add(Calendar.WEEK_OF_YEAR, 1)
                Repeat.WEEKDAYS -> cal.add(Calendar.DAY_OF_YEAR, 1)
                Repeat.NONE -> {}
            }
        }
        if (repeat == Repeat.WEEKDAYS) {
            while (isWeekend(cal)) cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}

/** Words for times, spoken or shown on screen. */
object TimeFmt {
    private val DAYS = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val MONTHS = arrayOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )

    /** 8 AM, 8:05 PM */
    fun spoken(ms: Long): String {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        val h24 = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        val ap = if (h24 < 12) "AM" else "PM"
        return if (m == 0) "$h12 $ap" else "$h12:${m.toString().padStart(2, '0')} $ap"
    }

    /** today at 6 PM, tomorrow at 8 AM, Friday at 7 AM, 25 November at 9 AM */
    fun humanWhen(ms: Long, now: Long = System.currentTimeMillis()): String {
        val diff = Math.round((startOfDay(ms) - startOfDay(now)) / 86_400_000.0).toInt()
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        val time = spoken(ms)
        return when {
            diff == 0 -> "today at $time"
            diff == 1 -> "tomorrow at $time"
            diff in 2..6 -> DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + " at " + time
            else -> c.get(Calendar.DAY_OF_MONTH).toString() + " " + MONTHS[c.get(Calendar.MONTH)] + " at " + time
        }
    }

    fun repeatText(r: Repeat): String = when (r) {
        Repeat.NONE -> ""
        Repeat.DAILY -> "every day"
        Repeat.WEEKDAYS -> "every weekday"
        Repeat.WEEKLY -> "every week"
    }

    /** What Jarvis says out loud when an alarm, reminder or timer goes off. */
    fun ringLine(kind: Kind, title: String, nowMs: Long): String {
        val t = spoken(nowMs)
        val c = Calendar.getInstance()
        c.timeInMillis = nowMs
        val hour = c.get(Calendar.HOUR_OF_DAY)
        val plain = title.isBlank() || title == "Alarm" || title == "Reminder"
        return when (kind) {
            Kind.ALARM ->
                if (hour in 4..11) {
                    "Good morning! It's $t. Time to wake up." + if (plain) "" else " Your alarm is for $title."
                } else {
                    "Sir, your alarm is ringing. It's $t." + if (plain) "" else " It is for $title."
                }
            Kind.REMINDER ->
                if (plain) "Sir, you asked me to remind you about something. It's $t."
                else "Sir, this is your reminder: $title. It's $t."
            Kind.TIMER -> "Sir, your $title has finished."
        }
    }

    private fun startOfDay(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}

package com.jarvis.assistant

enum class Kind { ALARM, REMINDER, TIMER }

enum class Repeat { NONE, DAILY, WEEKDAYS, WEEKLY }

/**
 * PENDING  = waiting for its time
 * RINGING  = ringing right now
 * DONE     = rang and was dismissed (one-shot) or was a finished timer
 * MISSED   = rang but nobody answered, or the phone was off at that time
 */
enum class Status { PENDING, RINGING, DONE, MISSED }

/**
 * baseAt    = the time the user asked for (a repeating alarm keeps its original time of day).
 * triggerAt = when it rings next. Differs from baseAt only while snoozed.
 */
data class Reminder(
    val id: Long,
    val title: String,
    val kind: Kind,
    val baseAt: Long,
    val triggerAt: Long,
    val repeat: Repeat,
    val status: Status,
    val snoozeCount: Int = 0
)

data class Task(val id: Long, val title: String, val done: Boolean)

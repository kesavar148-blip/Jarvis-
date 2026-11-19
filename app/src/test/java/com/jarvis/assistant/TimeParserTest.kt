package com.jarvis.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * "Now" in every test is Tuesday 17 November 2026, 22:40, so results do not depend on the real date.
 */
class TimeParserTest {

    private fun at(day: Int, hour: Int, minute: Int = 0, month: Int = Calendar.NOVEMBER): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(2026, month, day, hour, minute, 0)
        return c.timeInMillis
    }

    private val now = at(17, 22, 40)

    private fun parse(text: String) = TimeParser.parse(text, now)

    @Test
    fun wakeMeUpTomorrowAt8() {
        val r = parse("wake me up tomorrow at 8 AM")
        assertTrue(r.ok)
        assertEquals(at(18, 8), r.whenMs)
        assertEquals(Kind.ALARM, r.kind)
        assertEquals(Repeat.NONE, r.repeat)
        assertEquals("", r.title)
        assertFalse(r.guessedAmPm)
    }

    @Test
    fun remindMeToStudyAt6pm() {
        val r = parse("remind me to study at 6 PM")
        assertTrue(r.ok)
        assertEquals(at(18, 18), r.whenMs) // 6 PM today has passed, so tomorrow
        assertEquals(Kind.REMINDER, r.kind)
        assertEquals("study", r.title)
    }

    @Test
    fun callMeTomorrowMorning() {
        val r = parse("call me tomorrow morning at 7 AM")
        assertTrue(r.ok)
        assertEquals(at(18, 7), r.whenMs)
        assertTrue(r.callRequest)
        assertEquals(Kind.ALARM, r.kind)
        assertEquals("", r.title)
    }

    @Test
    fun wakeMeAt5EveryDay() {
        val r = parse("wake me up at 5 AM every day")
        assertTrue(r.ok)
        assertEquals(Repeat.DAILY, r.repeat)
        assertEquals(at(18, 5), r.whenMs)
        assertEquals("", r.title)
    }

    @Test
    fun relativeMinutes() {
        val r = parse("remind me in 30 minutes to call mom")
        assertTrue(r.ok)
        assertEquals(now + 30L * 60_000L, r.whenMs)
        assertEquals("call mom", r.title)
    }

    @Test
    fun relativeHalfAnHourAndAnHour() {
        assertEquals(now + 30L * 60_000L, parse("remind me in half an hour").whenMs)
        assertEquals(now + 60L * 60_000L, parse("remind me in an hour to stretch").whenMs)
        assertEquals(now + 2L * 3_600_000L, parse("remind me to call dad in 2 hours").whenMs)
        assertEquals("call dad", parse("remind me to call dad in 2 hours").title)
    }

    @Test
    fun explicitDayWithoutAmPmMeansMorningForNine() {
        val r = parse("remind me to attend my class tomorrow at 9")
        assertTrue(r.ok)
        assertEquals(at(18, 9), r.whenMs)
        assertTrue(r.guessedAmPm)
        assertEquals("attend my class", r.title)
    }

    @Test
    fun alarmAt630pm() {
        val r = parse("set an alarm for 6:30 pm")
        assertTrue(r.ok)
        assertEquals(at(18, 18, 30), r.whenMs)
        assertEquals(Kind.ALARM, r.kind)
        assertEquals("", r.title)
    }

    @Test
    fun timeAlreadyPassedToday() {
        val r = parse("remind me today at 9 am")
        assertFalse(r.ok)
        assertNotNull(r.error)
    }

    @Test
    fun everyWeekday() {
        val r = parse("wake me up every weekday at 6 am")
        assertTrue(r.ok)
        assertEquals(Repeat.WEEKDAYS, r.repeat)
        assertEquals(at(18, 6), r.whenMs) // Wednesday
    }

    @Test
    fun everyMonday() {
        val r = parse("remind me every monday at 10 am to submit report")
        assertTrue(r.ok)
        assertEquals(Repeat.WEEKLY, r.repeat)
        assertEquals(at(23, 10), r.whenMs)
        assertEquals("submit report", r.title)
    }

    @Test
    fun wakeMeOnFridayAt7() {
        val r = parse("wake me up on friday at 7")
        assertTrue(r.ok)
        assertEquals(at(20, 7), r.whenMs) // "wake" means morning
    }

    @Test
    fun nextMonday() {
        val r = parse("wake me up next monday at 6 am")
        assertTrue(r.ok)
        assertEquals(at(23, 6), r.whenMs)
    }

    @Test
    fun dayAfterTomorrow() {
        val r = parse("remind me day after tomorrow at 4 pm")
        assertTrue(r.ok)
        assertEquals(at(19, 16), r.whenMs)
    }

    @Test
    fun tonightAt11() {
        val r = parse("remind me tonight at 11 to lock the door")
        assertTrue(r.ok)
        assertEquals(at(17, 23), r.whenMs)
        assertEquals("lock the door", r.title)
    }

    @Test
    fun noTimeAsksAgain() {
        val r = parse("wake me up")
        assertFalse(r.ok)
        assertNotNull(r.error)
    }

    @Test
    fun noDayNoAmPmPicksSoonest() {
        val r = parse("remind me at 8 to take medicine")
        assertTrue(r.ok)
        assertEquals(at(18, 8), r.whenMs) // 8 AM and 8 PM today have both passed
        assertTrue(r.guessedAmPm)
        assertEquals("take medicine", r.title)
    }

    @Test
    fun dotInTime() {
        assertEquals(at(18, 8, 30), parse("set alarm for 8.30 am").whenMs)
    }

    @Test
    fun noon() {
        assertEquals(at(18, 12), parse("set alarm for noon").whenMs)
    }

    @Test
    fun numberWords() {
        assertEquals(at(18, 8), parse("wake me up at eight am").whenMs)
    }

    @Test
    fun everyMorningDefaultsTo8() {
        val r = parse("wake me up every morning")
        assertTrue(r.ok)
        assertEquals(Repeat.DAILY, r.repeat)
        assertEquals(at(18, 8), r.whenMs)
    }

    @Test
    fun invalidHour() {
        assertFalse(parse("wake me up at 25:00").ok)
    }

    @Test
    fun repeatRules() {
        assertEquals(at(19, 6), Schedule.nextAfter(at(18, 6), Repeat.DAILY, at(18, 6, 5)))
        assertEquals(at(23, 7), Schedule.nextAfter(at(20, 7), Repeat.WEEKDAYS, at(20, 7, 1))) // Fri -> Mon
        assertEquals(at(24, 8), Schedule.nextAfter(at(17, 8), Repeat.WEEKLY, now))
        assertNull(Schedule.nextAfter(at(17, 8), Repeat.NONE, now))
        // a phone that was off for three days: the next ring is the first one still ahead
        assertEquals(at(21, 6), Schedule.nextAfter(at(18, 6), Repeat.DAILY, at(20, 23)))
    }

    @Test
    fun weekdayReminderAskedForOnSaturdayStartsMonday() {
        assertEquals(at(23, 6), Schedule.firstOccurrence(at(21, 6), Repeat.WEEKDAYS)) // Saturday -> Monday
        assertEquals(at(21, 6), Schedule.firstOccurrence(at(21, 6), Repeat.DAILY))
    }

    @Test
    fun nextAtHelper() {
        assertEquals(at(18, 8), TimeParser.nextAt(8, 0, now))
        assertEquals(at(17, 23), TimeParser.nextAt(23, 0, now))
        assertEquals(at(23, 8), TimeParser.nextAt(8, 0, at(20, 22), weekdaysOnly = true)) // Fri night -> Monday
    }

    @Test
    fun spokenTimes() {
        assertEquals("8 AM", TimeFmt.spoken(at(18, 8)))
        assertEquals("8:05 PM", TimeFmt.spoken(at(18, 20, 5)))
        assertEquals("12 AM", TimeFmt.spoken(at(18, 0)))
        assertEquals("12:30 PM", TimeFmt.spoken(at(18, 12, 30)))
    }

    @Test
    fun humanDays() {
        assertEquals("today at 11 PM", TimeFmt.humanWhen(at(17, 23), now))
        assertEquals("tomorrow at 8 AM", TimeFmt.humanWhen(at(18, 8), now))
        assertEquals("Friday at 7 AM", TimeFmt.humanWhen(at(20, 7), now))
        assertEquals("30 November at 9 AM", TimeFmt.humanWhen(at(30, 9), now))
    }

    @Test
    fun ringLines() {
        assertEquals("Good morning! It's 8 AM. Time to wake up.", TimeFmt.ringLine(Kind.ALARM, "", at(18, 8)))
        assertEquals(
            "Good morning! It's 6 AM. Time to wake up. Your alarm is for gym.",
            TimeFmt.ringLine(Kind.ALARM, "gym", at(18, 6))
        )
        assertEquals("Sir, this is your reminder: study. It's 6 PM.", TimeFmt.ringLine(Kind.REMINDER, "study", at(18, 18)))
        assertEquals("Sir, your 5 minute timer has finished.", TimeFmt.ringLine(Kind.TIMER, "5 minute timer", at(18, 8)))
    }
}

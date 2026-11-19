package com.jarvis.assistant

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local SQLite database (built into Android, no extra library). Holds alarms/reminders/timers and the to-do list.
 * Everything stays on the phone.
 */
class ReminderDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "jarvis.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE reminders (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "title TEXT NOT NULL, " +
                "kind TEXT NOT NULL, " +
                "base_at INTEGER NOT NULL, " +
                "trigger_at INTEGER NOT NULL, " +
                "repeat TEXT NOT NULL, " +
                "status TEXT NOT NULL, " +
                "snoozes INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE tasks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "title TEXT NOT NULL, " +
                "done INTEGER NOT NULL DEFAULT 0, " +
                "created_at INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first schema. Add migrations here when it changes.
    }

    companion object {
        private const val COLS = "id, title, kind, base_at, trigger_at, repeat, status, snoozes"

        @Volatile
        private var instance: ReminderDb? = null

        fun of(ctx: Context): ReminderDb =
            instance ?: synchronized(this) { instance ?: ReminderDb(ctx).also { instance = it } }
    }

    // ---------- Reminders ----------

    fun insert(r: Reminder): Reminder {
        val id = writableDatabase.insert("reminders", null, values(r))
        return r.copy(id = id)
    }

    fun update(r: Reminder) {
        writableDatabase.update("reminders", values(r), "id = ?", arrayOf(r.id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("reminders", "id = ?", arrayOf(id.toString()))
    }

    fun find(id: Long): Reminder? =
        readableDatabase.rawQuery("SELECT $COLS FROM reminders WHERE id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst()) read(c) else null }

    /** Waiting or ringing, soonest first. */
    fun active(): List<Reminder> = list("status IN ('PENDING','RINGING')")

    /** What the Reminders screen shows: active plus missed. Finished ones older than a week are removed. */
    fun forScreen(): List<Reminder> {
        val weekAgo = System.currentTimeMillis() - 7L * 86_400_000L
        writableDatabase.delete("reminders", "status = 'DONE' AND trigger_at < ?", arrayOf(weekAgo.toString()))
        return list("status IN ('PENDING','RINGING','MISSED')")
    }

    private fun list(where: String): List<Reminder> {
        val out = ArrayList<Reminder>()
        readableDatabase.rawQuery("SELECT $COLS FROM reminders WHERE $where ORDER BY trigger_at ASC", null)
            .use { c -> while (c.moveToNext()) out.add(read(c)) }
        return out
    }

    private fun read(c: Cursor) = Reminder(
        id = c.getLong(0),
        title = c.getString(1),
        kind = Kind.valueOf(c.getString(2)),
        baseAt = c.getLong(3),
        triggerAt = c.getLong(4),
        repeat = Repeat.valueOf(c.getString(5)),
        status = Status.valueOf(c.getString(6)),
        snoozeCount = c.getInt(7)
    )

    private fun values(r: Reminder): ContentValues {
        val v = ContentValues()
        v.put("title", r.title)
        v.put("kind", r.kind.name)
        v.put("base_at", r.baseAt)
        v.put("trigger_at", r.triggerAt)
        v.put("repeat", r.repeat.name)
        v.put("status", r.status.name)
        v.put("snoozes", r.snoozeCount)
        return v
    }

    // ---------- To-do list ----------

    fun addTask(title: String): Long {
        val v = ContentValues()
        v.put("title", title)
        v.put("done", 0)
        v.put("created_at", System.currentTimeMillis())
        return writableDatabase.insert("tasks", null, v)
    }

    fun pendingTasks(): List<Task> {
        val out = ArrayList<Task>()
        readableDatabase.rawQuery("SELECT id, title, done FROM tasks WHERE done = 0 ORDER BY id ASC", null)
            .use { c -> while (c.moveToNext()) out.add(Task(c.getLong(0), c.getString(1), c.getInt(2) != 0)) }
        return out
    }

    fun completeTask(id: Long) {
        val v = ContentValues()
        v.put("done", 1)
        writableDatabase.update("tasks", v, "id = ?", arrayOf(id.toString()))
    }
}

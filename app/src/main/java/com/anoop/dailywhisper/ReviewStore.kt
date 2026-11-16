package com.anoop.dailywhisper

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate

/**
 * Weekly reviews, kept on this phone in their own SQLite file ("reviews.db", one row per week: the week's first day,
 * when it was made, on the phone or online, and the review as JSON). Separate from notes.db so the notes' schema and
 * its upgrades are untouched; deleting it loses only reviews, which can be made again.
 */
class ReviewStore private constructor(c: Context) : SQLiteOpenHelper(c, NAME, null, VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE reviews (week_start TEXT PRIMARY KEY, created INTEGER NOT NULL, source TEXT NOT NULL, json TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun save(r: WeeklyReview.Review) {
        writableDatabase.insertWithOnConflict("reviews", null, ContentValues().apply {
            put("week_start", r.start.toString()); put("created", r.created); put("source", r.source); put("json", r.toJson())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun get(weekStart: LocalDate): WeeklyReview.Review? =
        readableDatabase.rawQuery("SELECT json FROM reviews WHERE week_start = ?", arrayOf(weekStart.toString())).use { c ->
            if (c.moveToFirst()) WeeklyReview.load(c.getString(0)) else null
        }

    /** Every saved week, newest first. */
    fun weeks(): List<LocalDate> = readableDatabase.rawQuery("SELECT week_start FROM reviews ORDER BY week_start DESC", null).use { c ->
        buildList { while (c.moveToNext()) runCatching { LocalDate.parse(c.getString(0)) }.getOrNull()?.let { add(it) } }
    }

    fun delete(weekStart: LocalDate) { writableDatabase.delete("reviews", "week_start = ?", arrayOf(weekStart.toString())) }

    companion object {
        const val NAME = "reviews.db"
        const val VERSION = 1
        @Volatile private var instance: ReviewStore? = null
        fun get(c: Context): ReviewStore = instance ?: synchronized(this) {
            instance ?: ReviewStore(c.applicationContext).also { instance = it }
        }
        /** Tests: forget the open database (a new app context each test). */
        internal fun reset() { instance?.close(); instance = null }
    }
}

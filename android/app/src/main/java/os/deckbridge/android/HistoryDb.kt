package os.deckbridge.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import os.deckbridge.logic.HistoryRow

class HistoryDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "deckbridge-history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE session (id INTEGER PRIMARY KEY AUTOINCREMENT, started INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE entry (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL, at INTEGER NOT NULL, deck INTEGER NOT NULL, title TEXT, artist TEXT, bpm REAL, key_name TEXT, seconds INTEGER)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun session(now: Long): Long {
        val db = writableDatabase
        val existing = db.rawQuery("SELECT id FROM session ORDER BY id DESC LIMIT 1", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        }
        if (existing > 0) return existing
        db.execSQL("INSERT INTO session (started) VALUES (?)", arrayOf(now))
        return db.rawQuery("SELECT last_insert_rowid()", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
    }

    fun insert(sessionId: Long, row: HistoryRow) {
        writableDatabase.execSQL(
            "INSERT INTO entry (session_id, at, deck, title, artist, bpm, key_name, seconds) VALUES (?,?,?,?,?,?,?,?)",
            arrayOf(sessionId, row.atMillis, row.deck, row.title, row.artist, row.bpm, row.keyName, row.seconds),
        )
    }

    fun rows(): List<HistoryRow> {
        val out = mutableListOf<HistoryRow>()
        readableDatabase.rawQuery(
            "SELECT at, deck, title, artist, bpm, key_name, seconds FROM entry ORDER BY at DESC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += HistoryRow(
                    atMillis = cursor.getLong(0),
                    deck = cursor.getInt(1),
                    title = cursor.getString(2).orEmpty(),
                    artist = cursor.getString(3).orEmpty(),
                    bpm = cursor.getDouble(4),
                    keyName = cursor.getString(5).orEmpty(),
                    seconds = cursor.getInt(6),
                )
            }
        }
        return out
    }
}

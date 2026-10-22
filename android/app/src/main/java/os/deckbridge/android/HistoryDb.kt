package os.deckbridge.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import os.deckbridge.logic.HistoryRow

class HistoryDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "deckbridge-history.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE session (id INTEGER PRIMARY KEY AUTOINCREMENT, started INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE entry (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL, at INTEGER NOT NULL, deck INTEGER NOT NULL, title TEXT, artist TEXT, bpm REAL, played_bpm REAL, key_name TEXT, seconds INTEGER, track_id INTEGER)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE entry ADD COLUMN played_bpm REAL")
            db.execSQL("ALTER TABLE entry ADD COLUMN track_id INTEGER")
        }
    }

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
            "INSERT INTO entry (session_id, at, deck, title, artist, bpm, played_bpm, key_name, seconds, track_id) VALUES (?,?,?,?,?,?,?,?,?,?)",
            arrayOf(sessionId, row.atMillis, row.deck, row.title, row.artist, row.bpm, row.playedBpm, row.keyName, row.seconds, row.trackId),
        )
    }

    fun rows(): List<HistoryRow> {
        val out = mutableListOf<HistoryRow>()
        readableDatabase.rawQuery(
            "SELECT at, deck, title, artist, bpm, played_bpm, key_name, seconds, track_id FROM entry ORDER BY at DESC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val bpm = cursor.getDouble(4)
                out += HistoryRow(
                    atMillis = cursor.getLong(0),
                    deck = cursor.getInt(1),
                    title = cursor.getString(2).orEmpty(),
                    artist = cursor.getString(3).orEmpty(),
                    bpm = bpm,
                    playedBpm = if (cursor.isNull(5)) bpm else cursor.getDouble(5),
                    keyName = cursor.getString(6).orEmpty(),
                    seconds = cursor.getInt(7),
                    trackId = if (cursor.isNull(8)) -1 else cursor.getInt(8),
                )
            }
        }
        return out
    }
}

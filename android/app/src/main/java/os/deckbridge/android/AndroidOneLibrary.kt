package os.deckbridge.android

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import os.deckbridge.logic.ONE_LIBRARY_PUBLIC_KEY
import os.deckbridge.logic.OneLibraryOpener
import os.deckbridge.logic.OneLibraryRead
import java.io.File

/**
 * Abre uma cópia local de exportLibrary.db. Não cria WAL no pendrive.
 * Uma tentativa, com a chave pública documentada e o SQLCipher 4 padrão.
 */
class AndroidOneLibrary(private val context: Context) : OneLibraryOpener {
    override fun open(bytes: ByteArray): OneLibraryRead? {
        if (bytes.size < 16) return null
        val dir = File(context.cacheDir, "onelibrary")
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, "exportLibrary-copy.db")
        file.writeBytes(bytes)
        File(file.parentFile, file.name + "-wal").delete()
        File(file.parentFile, file.name + "-shm").delete()
        return try {
            System.loadLibrary("sqlcipher")
            val database = SQLiteDatabase.openDatabase(
                file.absolutePath,
                ONE_LIBRARY_PUBLIC_KEY.toByteArray(Charsets.UTF_8),
                null,
                SQLiteDatabase.OPEN_READONLY,
                null,
            )
            database.use { db ->
                val tables = mutableListOf<String>()
                db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", null).use { cursor ->
                    while (cursor.moveToNext()) tables += cursor.getString(0)
                }
                val content = if (tables.any { it.equals("djmdContent", ignoreCase = true) }) {
                    db.rawQuery("SELECT COUNT(*) FROM djmdContent", null).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) else 0
                    }
                } else {
                    0
                }
                OneLibraryRead(tables, content)
            }
        } catch (_: Throwable) {
            null
        } finally {
            file.delete()
            File(file.parentFile, file.name + "-wal").delete()
            File(file.parentFile, file.name + "-shm").delete()
        }
    }
}

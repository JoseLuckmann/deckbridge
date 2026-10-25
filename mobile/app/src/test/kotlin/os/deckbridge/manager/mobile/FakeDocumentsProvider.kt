package os.deckbridge.manager.mobile

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.FileNotFoundException

/**
 * Um provedor de documentos de brinquedo sobre uma pasta, para testar o [SafStorage] sem pendrive. Imita o provedor de
 * armazenamento externo do Android: ids são caminhos, e renomear por cima de um nome existente falha. Não herda de
 * DocumentsProvider porque o Robolectric chama o `query` antigo, que essa classe recusa.
 */
class FakeDocumentsProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "os.deckbridge.test.docs"
        @Volatile var base: File = File(".")
        /** Se definido, a próxima renomeação PARA este nome falha (uma vez) (pendrive que não deixa), para testar a volta do arquivo antigo. */
        @Volatile var failRenameTo: String? = null
    }

    private val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)

    override fun onCreate() = true

    private fun file(id: String) = if (id == "root") base else File(base, id)
    private fun idOf(f: File) = f.relativeTo(base).path.ifEmpty { "root" }
    private fun uriFor(tree: Uri, id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private fun add(c: MatrixCursor, f: File) {
        val flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or (if (f.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE)
        c.addRow(arrayOf(idOf(f), if (f == base) "root" else f.name, if (f.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream", f.length(), f.lastModified(), flags))
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val id = DocumentsContract.getDocumentId(uri)
        val wanted = projection?.map { it } ?: cols.toList()
        val c = MatrixCursor(wanted.toTypedArray())
        val full = MatrixCursor(cols)
        if (uri.pathSegments.lastOrNull() == "children") file(id).listFiles()?.forEach { add(full, it) }
        else file(id).takeIf { it.exists() }?.let { add(full, it) }
        // Devolve só as colunas pedidas, na ordem pedida (quem consulta lê por posição).
        while (full.moveToNext()) c.addRow(wanted.map { col -> full.getColumnIndex(col).takeIf { it >= 0 }?.let { i -> if (col == Document.COLUMN_SIZE || col == Document.COLUMN_LAST_MODIFIED || col == Document.COLUMN_FLAGS) full.getLong(i) else full.getString(i) } })
        return c
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uri = extras!!.getParcelable<Uri>("uri")!!
        val id = DocumentsContract.getDocumentId(uri)
        return when (method) {
            "android:createDocument" -> {
                val name = extras.getString(Document.COLUMN_DISPLAY_NAME)!!
                val f = File(file(id), name)
                check(!f.exists()) { "já existe" }
                if (extras.getString(Document.COLUMN_MIME_TYPE) == Document.MIME_TYPE_DIR) f.mkdirs() else f.createNewFile()
                Bundle().apply { putParcelable("uri", uriFor(uri, idOf(f))) }
            }
            "android:deleteDocument" -> {
                file(id).deleteRecursively()
                Bundle()
            }
            "android:renameDocument" -> {
                val name = extras.getString(Document.COLUMN_DISPLAY_NAME)!!
                if (name == failRenameTo) { failRenameTo = null; throw IllegalStateException("rename recusado") }
                val f = file(id)
                val to = File(f.parentFile, name)
                check(!to.exists()) { "já existe" }
                check(f.renameTo(to))
                Bundle().apply { putParcelable("uri", uriFor(uri, idOf(to))) }
            }
            else -> null
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = file(DocumentsContract.getDocumentId(uri))
        if (!f.exists()) throw FileNotFoundException(uri.toString())
        val m = if (mode.startsWith("r")) ParcelFileDescriptor.MODE_READ_ONLY else ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        return ParcelFileDescriptor.open(f, m)
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

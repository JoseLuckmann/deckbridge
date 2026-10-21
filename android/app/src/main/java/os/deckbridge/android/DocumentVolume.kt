package os.deckbridge.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import os.deckbridge.logic.ReadableVolume

class DocumentVolume(
    private val context: Context,
    treeUri: Uri,
    override val label: String,
) : ReadableVolume {
    override val simulated: Boolean = false
    private val root: DocumentFile? = DocumentFile.fromTreeUri(context, treeUri)

    override fun exists(path: String): Boolean = resolve(path)?.isFile == true

    override fun read(path: String): ByteArray? {
        val file = resolve(path) ?: return null
        return context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
    }

    private fun resolve(path: String): DocumentFile? {
        var current = root ?: return null
        val parts = path.split('/').filter { it.isNotEmpty() }
        parts.forEachIndexed { index, part ->
            val child = current.listFiles().firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
            if (index == parts.lastIndex) return child.takeIf { it.isFile }
            if (!child.isDirectory) return null
            current = child
        }
        return null
    }
}

fun persistedRekordboxVolumes(context: Context): List<DocumentVolume> {
    return context.contentResolver.persistedUriPermissions.mapNotNull { permission ->
        if (!permission.isReadPermission) return@mapNotNull null
        val volume = DocumentVolume(context, permission.uri, permission.uri.lastPathSegment ?: "volume")
        if (volume.exists("PIONEER/rekordbox/export.pdb") ||
            volume.exists("rekordbox/export.pdb") ||
            volume.exists("export.pdb")
        ) {
            volume
        } else {
            null
        }
    }
}

fun removableVolumeLabel(context: Context): String {
    val manager = context.getSystemService(StorageManager::class.java) ?: return "nenhum"
    val mounted = manager.storageVolumes.filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
    if (mounted.isEmpty()) return "nenhum volume removível montado"
    return mounted.joinToString(", ") { volume ->
        val name = if (Build.VERSION.SDK_INT >= 24) volume.getDescription(context) else "removível"
        name ?: "removível"
    }
}

@Suppress("DEPRECATION")
fun removableInitialUri(context: Context): Uri? {
    if (Build.VERSION.SDK_INT < 29) return null
    val manager = context.getSystemService(StorageManager::class.java) ?: return null
    val volume = manager.storageVolumes.firstOrNull { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
        ?: return null
    val intent = volume.createOpenDocumentTreeIntent()
    return intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI)
}

fun keepReadPermission(context: Context, uri: Uri) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    context.contentResolver.takePersistableUriPermission(uri, flags)
}

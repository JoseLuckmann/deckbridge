package os.deckbridge.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import os.deckbridge.logic.RbTrack
import os.deckbridge.logic.ReadableVolume
import os.deckbridge.logic.StagePalette
import os.deckbridge.logic.readArtworkFile
import java.io.ByteArrayOutputStream
import java.io.File

object ArtworkStore {
    private val memory = object : LinkedHashMap<String, ByteArray>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 64
    }

    /** Miniatura na pasta de cache do app. O volume só é lido. */
    fun thumb(cacheDir: File, volume: ReadableVolume, track: RbTrack): ByteArray? {
        val path = track.artworkPath
        if (path.isBlank()) return null
        synchronized(memory) {
            memory[path]?.let { return it }
        }
        val file = File(cacheDir, "artwork/" + path.hashCode().toUInt().toString(16) + ".png")
        if (file.isFile && file.length() > 16) {
            val cached = file.readBytes()
            synchronized(memory) { memory[path] = cached }
            return cached
        }
        val source = readArtworkFile(volume, path) ?: return null
        val stored = scalePng(source) ?: source
        file.parentFile?.mkdirs()
        file.writeBytes(stored)
        synchronized(memory) { memory[path] = stored }
        return stored
    }

    private fun scalePng(source: ByteArray): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size) ?: return null
        val size = 80
        val scaled = if (bitmap.width == size && bitmap.height == size) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, size, size, true)
        }
        val out = ByteArrayOutputStream()
        val wrote = scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return if (wrote) out.toByteArray() else null
    }
}

@Composable
fun CoverTile(bytes: ByteArray?, title: String, modifier: Modifier = Modifier) {
    val image = remember(bytes) {
        if (bytes == null || bytes.isEmpty()) {
            null
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
    Box(modifier.background(Color(StagePalette.panelRaised)), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(bitmap = image, contentDescription = title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            TextInk("♪", color = Color(StagePalette.accentBlue), fontSize = 14.sp)
        }
    }
}

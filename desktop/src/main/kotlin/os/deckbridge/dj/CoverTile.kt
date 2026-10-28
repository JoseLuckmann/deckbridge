package os.deckbridge.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import os.deckbridge.logic.StagePalette

/** A capa na lista e no deck, no computador (o celular tem a sua, em `CoverArt.kt`, com o decodificador do Android). */
@Composable
fun CoverTile(bytes: ByteArray?, title: String, modifier: Modifier = Modifier) {
    val image = remember(bytes) {
        if (bytes == null || bytes.isEmpty()) null else runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
    }
    Box(modifier.background(Color(StagePalette.panelRaised)), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(bitmap = image, contentDescription = title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            TextInk("♪", color = Color(StagePalette.accentBlue), fontSize = 14.sp)
        }
    }
}

package os.deckbridge.session

import java.io.File
import os.deckbridge.logic.FolderVolume
import os.deckbridge.logic.RbCatalog
import os.deckbridge.logic.decodeWavMono
import os.deckbridge.logic.loadPlainFolder

/** Pasta de entrada do vídeo de sessão (WAV 48 kHz). Os áudios do usuário não entram no git. */
object SessionAssets {
    val dir: File = File(System.getenv("SESSION_DIR") ?: "/tmp/deckbridge-session")
    val input: File get() = File(dir, "in")

    fun enabled(): Boolean = input.isDirectory && (input.listFiles()?.size ?: 0) >= 2

    fun catalog(): RbCatalog {
        val cache = File(dir, "cache").apply { mkdirs() }
        return loadPlainFolder(FolderVolume(input, "pasta de teste"), cache, { _, bytes ->
            val pcm = decodeWavMono(bytes) ?: return@loadPlainFolder null
            pcm.samples to pcm.rate
        })
    }
}

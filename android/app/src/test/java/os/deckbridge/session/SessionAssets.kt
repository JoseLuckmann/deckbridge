package os.deckbridge.session

import java.io.File
import os.deckbridge.logic.FolderVolume
import os.deckbridge.logic.RbCatalog
import os.deckbridge.logic.decodeWavMono
import os.deckbridge.logic.lacksDetail
import os.deckbridge.logic.loadPlainFolder
import os.deckbridge.logic.wavesFromPcm
import os.deckbridge.logic.withDetail

/** Pasta de entrada do vídeo de sessão (WAV 48 kHz). Os áudios do usuário não entram no git. */
object SessionAssets {
    val dir: File = File(System.getenv("SESSION_DIR") ?: "/tmp/deckbridge-session")
    val input: File get() = File(dir, "in")

    fun enabled(): Boolean = input.isDirectory && (input.listFiles()?.size ?: 0) >= 2

    fun catalog(): RbCatalog {
        val cache = File(dir, "cache").apply { mkdirs() }
        val volume = FolderVolume(input, "pasta de teste")
        val catalog = loadPlainFolder(volume, cache, { _, bytes ->
            val pcm = decodeWavMono(bytes) ?: return@loadPlainFolder null
            pcm.samples to pcm.rate
        })
        // Como o DeckPage faz na carga: o cache da pasta não guarda o detalhe da onda.
        val analyses = catalog.analyses.mapValues { (id, analysis) ->
            val track = catalog.tracks.firstOrNull { it.id == id }
            val pcm = track?.let { volume.read(it.filePath) }?.let(::decodeWavMono)
            if (pcm == null || !analysis.lacksDetail()) analysis else analysis.withDetail(wavesFromPcm(pcm.samples, pcm.rate))
        }
        return catalog.copy(analyses = analyses)
    }
}

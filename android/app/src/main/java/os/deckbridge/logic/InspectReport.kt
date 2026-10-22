package os.deckbridge.logic

import java.io.File

/** Volume de uma pasta no disco. Só lê. Não cria nem altera arquivos dentro dela. */
class FolderVolume(
    private val root: File,
    override val label: String = root.name,
) : ReadableVolume {
    override val simulated: Boolean = false

    override fun exists(path: String): Boolean = resolve(path)?.isFile == true

    override fun read(path: String): ByteArray? {
        val file = resolve(path) ?: return null
        if (!file.isFile) return null
        return file.readBytes()
    }

    private fun resolve(path: String): File? {
        val norm = path.replace('\\', '/').trim().trimStart('/')
        if (norm.isBlank() || norm.contains("..")) return null
        val rootPath = root.canonicalFile
        val target = File(rootPath, norm).canonicalFile
        val prefix = rootPath.path + File.separator
        if (target.path != rootPath.path && !target.path.startsWith(prefix)) return null
        return target
    }
}

/** Relatório de uma biblioteca já lida. Não escreve no volume. */
fun inspectText(catalog: RbCatalog): String {
    val lines = ArrayList<String>()
    lines += "origem: ${catalog.label}"
    lines += if (catalog.label.contains("export deste app")) {
        "tipo: export deste app relido de uma pasta. Não é rekordbox."
    } else {
        "tipo: pasta lida. simulada=${catalog.simulated}"
    }
    lines += "simulada: ${catalog.simulated}"
    lines += "erro: ${catalog.error ?: "nenhum"}"
    lines += "avisos: ${catalog.warnings.size}"
    catalog.warnings.take(16).forEach { lines += "  aviso: $it" }
    lines += "faixas: ${catalog.tracks.size}"
    catalog.tracks.take(40).forEach { track ->
        lines += "  ${track.id} ${track.title} — ${track.artist}  ${"%.1f".format(track.bpm)} BPM  ${track.keyName.ifBlank { "—" }}  ${track.durationSeconds}s"
    }
    if (catalog.tracks.size > 40) lines += "  … ${catalog.tracks.size - 40} faixas a mais"
    lines += "playlists: ${catalog.playlists.size}"
    catalog.playlists.forEach { playlist ->
        val kind = if (playlist.folder) "pasta" else "lista"
        lines += "  ${playlist.id} $kind ${playlist.name} faixas=${playlist.trackIds.size}"
    }
    var hot = 0
    var memory = 0
    var loops = 0
    var pwv5 = 0
    var pwv4 = 0
    var pwv6 = 0
    var pwv7 = 0
    var pwv3 = 0
    var pwav = 0
    catalog.analyses.values.forEach { analysis ->
        hot += analysis.hotCues.size
        memory += analysis.memoryCues.size
        loops += analysis.memoryCues.count { it.loopEndMs != null }
        if (analysis.rgbDetail != null) pwv5 += 1
        if (analysis.rgbPreview != null) pwv4 += 1
        if (analysis.bandDetail != null) pwv7 += 1
        if (analysis.bandPreview != null) pwv6 += 1
        if (analysis.blueDetail.isNotEmpty()) pwv3 += 1
        if (analysis.waveform.isNotEmpty() || analysis.bluePreview.isNotEmpty()) pwav += 1
    }
    lines += "hot cues: $hot"
    lines += "memory cues: $memory"
    lines += "loops de memory: $loops"
    lines += "faixas com PWV5: $pwv5"
    lines += "faixas com PWV4: $pwv4"
    lines += "faixas com PWV7: $pwv7"
    lines += "faixas com PWV6: $pwv6"
    lines += "faixas com PWV3: $pwv3"
    lines += "faixas com PWAV ou preview azul: $pwav"
    lines += "DEVSETTING cor: ${catalog.usbWaveColor ?: "ausente"}"
    lines += "DEVSETTING playhead: ${catalog.usbPlayhead?.let { "%.2f".format(it) } ?: "ausente"}"
    lines += "DEVSETTING nota: ${catalog.usbWaveNote.ifBlank { "—" }}"
    lines += "Somente leitura. Nada foi gravado no volume."
    return lines.joinToString("\n")
}

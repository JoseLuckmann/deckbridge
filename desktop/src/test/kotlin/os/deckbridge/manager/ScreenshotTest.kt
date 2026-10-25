package os.deckbridge.manager

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.io.File
import java.nio.file.Files
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.TrackPatch
import os.deckbridge.manager.ui.App
import androidx.compose.ui.geometry.Offset
import os.deckbridge.manager.ui.DragInfo
import os.deckbridge.manager.ui.UiState
import os.deckbridge.manager.ui.refreshSuggestions
import os.deckbridge.manager.ui.startImport
import os.deckbridge.manager.volume.Backups

/** Capturas offscreen da interface (sem janela do sistema). Só com SHOTS_DIR: `SHOTS_DIR=/pasta ./gradlew test --tests '*ScreenshotTest'`. */
class ScreenshotTest {
    private fun shoot(out: File, name: String, model: ManagerModel, ui: UiState = UiState(), w: Int = 1360, h: Int = 820) {
        val scene = ImageComposeScene(w, h, Density(1f)) { App(model, ui) }
        try {
            scene.render(0)
            scene.render(50_000_000)
            val data = scene.render(100_000_000).encodeToData(EncodedImageFormat.PNG)!!
            File(out, "$name.png").writeBytes(data.bytes)
        } finally {
            scene.close()
        }
    }

    @Test
    fun renderTheScreens() {
        val dir = System.getenv("SHOTS_DIR")
        assumeTrue("SHOTS_DIR não definido", dir != null)
        val out = File(dir!!).apply { mkdirs() }
        val usb = TestVolumes.synthetic()
        val backups = Backups(TestVolumes.tempDir("dbm-shots"))

        shoot(out, "01-boas-vindas", ManagerModel(backups))

        val m = ManagerModel(backups)
        m.open(usb)
        val v = m.view!!
        m.selection = setOf(v.tracks[3].id)
        shoot(out, "02-colecao", m)

        m.submit(Edit.SetTracks(listOf(v.tracks[3].id), TrackPatch(title = "Night Bus (Extended)", artist = "Nara Vale & Lumen K", genre = "Deep House", rating = 4)), "x")
        val next = m.nextPlaylistId()
        m.submit(Edit.CreatePlaylist(next, 0, "Set de sábado", false), "Criar playlist \"Set de sábado\"")
        m.submit(Edit.AddToPlaylist(next, v.tracks.take(6).map { it.id }), "Adicionar 6 faixas a \"Set de sábado\"")
        m.openPlaylist = next
        m.sort = Sort(null)
        m.selection = setOf(v.tracks[2].id)
        val ui = UiState().apply { expanded = v.playlists.filter { it.folder }.map { it.id }.toSet() }
        shoot(out, "03-playlist-com-fila", m, ui)

        m.selection = setOf(v.tracks[1].id, v.tracks[2].id, v.tracks[4].id)
        m.openPlaylist = null
        m.sort = Sort(Column.ARTIST)
        shoot(out, "04-varias-faixas", m, ui)

        ui.review = true
        shoot(out, "05-revisar-e-aplicar", m, ui)
        ui.review = false

        m.apply()
        m.submit(Edit.SetTracks(listOf(v.tracks[0].id), TrackPatch(comment = "tocar na abertura")), "Faixa 1: comentário")
        ui.backups = true
        shoot(out, "06-backups", m, ui)
    
        phaseTwo(out, usb, backups)
    }

    private fun phaseTwo(out: File, usb: File, backups: Backups) {
        val m = ManagerModel(backups)
        m.open(usb)
        val v = m.view!!
        val withAnalysis = v.tracks.first { m.hasAnalysis(it) && m.trackFiles(it).cues.isNotEmpty() }
        m.selection = setOf(withAnalysis.id)
        m.sort = Sort(Column.TITLE)

        val ui = UiState()
        ui.cueTrack = withAnalysis.id
        shoot(out, "07-cues-e-grade", m, ui)
        ui.cueTrack = null

        // Importar: um WAV sintético vira linha analisada.
        val src = File(out, "Nara Vale - Corredor de Vidro.wav")
        src.writeBytes(os.deckbridge.logic.encodeWavMono16(os.deckbridge.logic.click(bpm = 126.0, seconds = 40, rate = 22050, freq = 330.0), 22050))
        val rows = startImport(listOf(src, File(out, "Faixa Que Falta.mp3")))
        val end = System.currentTimeMillis() + 90_000
        while (rows.any { it.status.startsWith("lendo") } && System.currentTimeMillis() < end) Thread.sleep(50)
        ui.importRows = rows
        shoot(out, "08-importar", m, ui)
        ui.importRows = null
        src.delete()

        ui.rename = true
        m.selection = emptySet()
        shoot(out, "09-renomear", m, ui)
        ui.rename = false

        // Organizar: bagunça um pouco a cópia para haver o que sugerir.
        val messy = ManagerModel(backups)
        messy.open(usb)
        val mv = messy.view!!
        messy.submit(Edit.SetTracks(listOf(mv.tracks[2].id), TrackPatch(title = "  NIGHT   BUS  ", artist = "NARA VALE")), "x")
        messy.submit(Edit.SetTracks(listOf(mv.tracks[5].id), TrackPatch(genre = "house   ", album = "WARM CONCRETE")), "y")
        val keep = messy.pending.size
        messy.apply()
        ui.organize = true
        refreshSuggestions(messy, ui.organizeState)
        shoot(out, "10-organizar", messy, ui)
        ui.organize = false

        // Exportar para um pendrive que já tem biblioteca (outro volume sintético).
        val other = TestVolumes.synthetic()
        val tr = os.deckbridge.manager.ui.TransferState(export = true)
        tr.target = other
        tr.existing = os.deckbridge.manager.transfer.UsbExporter(backups.base).inspect(os.deckbridge.manager.transfer.FileStorage(other))
        ui.transfer = tr
        shoot(out, "12-exportar", m, ui)
        ui.transfer = null
        ui.organize = false

        // Arrastar faixas até uma playlist.
        val pl = v.playlists.first { !it.folder && v.entries[it.id].orEmpty().size in 1 until v.tracks.size }
        val drag = UiState().apply { expanded = v.playlists.filter { it.folder }.map { it.id }.toSet() }
        val scene = androidx.compose.ui.ImageComposeScene(1360, 820, androidx.compose.ui.unit.Density(1f)) { App(m, drag) }
        try {
            scene.render(0)
            scene.render(50_000_000)
            val target = drag.rects["pl:${pl.id}"]
            if (target != null) {
                drag.drag = DragInfo(listOf(withAnalysis.id), withAnalysis.title, Offset(target.center.x, target.center.y))
                drag.updateHover()
            }
            scene.render(100_000_000)
            val data = scene.render(150_000_000).encodeToData(EncodedImageFormat.PNG)!!
            File(out, "11-arrastar.png").writeBytes(data.bytes)
        } finally {
            scene.close()
        }
        check(keep == 2)
    }
}

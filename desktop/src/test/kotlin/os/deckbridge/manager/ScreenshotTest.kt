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
import os.deckbridge.manager.ui.UiState
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
    }
}

package os.deckbridge.manager.mobile

import java.io.File
import os.deckbridge.logic.click
import os.deckbridge.logic.encodeWavMono16
import os.deckbridge.manager.anlz.Cue
import os.deckbridge.manager.importer.ImportPrep
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.TrackPatch

/** Biblioteca de demonstração só para os testes: faixas sintéticas (cliques no andamento certo), nomes inventados. */
object DemoLibrary {
    private class Spec(val artist: String, val title: String, val bpm: Double, val genre: String, val rating: Int, val color: Int)

    private val specs = listOf(
        Spec("Aurora Drive", "Night Signal", 124.0, "House", 5, 5),
        Spec("Aurora Drive", "Glass Harbour", 126.0, "House", 4, 5),
        Spec("Lumen Park", "Tidal Bloom", 122.0, "Deep House", 3, 6),
        Spec("Lumen Park", "Soft Machines", 120.0, "Deep House", 0, 0),
        Spec("Vera Kline", "Concrete Garden", 128.0, "Techno", 4, 8),
        Spec("Vera Kline", "Afterhours", 130.0, "Techno", 5, 8),
        Spec("Mono Cinema", "Slow Pulse", 118.0, "Nu Disco", 2, 4),
        Spec("Mono Cinema", "Velvet Rail", 121.0, "Nu Disco", 3, 3),
        Spec("Northline", "Copper Sky", 125.0, "House", 0, 0),
        Spec("Northline", "Small Hours", 123.0, "House", 1, 0),
    )

    fun build(app: ManagerApp, seconds: Int = 24) {
        app.start()
        val src = File(app.root.parentFile, "origem").apply { mkdirs() }
        val edits = specs.mapIndexed { n, s ->
            val f = File(src, "${s.artist} - ${s.title}.wav")
            f.writeBytes(encodeWavMono16(click(bpm = s.bpm, seconds = seconds, rate = 22050, freq = 220.0 + n * 40), 22050))
            val p = ImportPrep.prepare(f)
            Edit.ImportTrack(p, s.title, s.artist, "", s.genre, p.read.keyName, 2025, n + 1, p.suggestedCues) to "Importar ${s.title}"
        }
        check(app.model.submitAll(edits) == specs.size) { app.model.banner?.text.orEmpty() }
        check(app.model.apply(null) != null) { app.model.banner?.text.orEmpty() }
        val byTitle = app.model.view!!.tracks.associateBy { it.title }
        for (s in specs) {
            val t = byTitle.getValue(s.title)
            app.model.submit(Edit.SetTracks(listOf(t.id), TrackPatch(rating = s.rating, colorId = s.color)), "marcar")
        }
        fun ids(vararg titles: String) = titles.map { byTitle.getValue(it).id }
        app.model.submit(Edit.CreatePlaylist(1, 0, "Sexta à noite", false), "p1")
        app.model.submit(Edit.AddToPlaylist(1, ids("Night Signal", "Tidal Bloom", "Concrete Garden", "Afterhours")), "p1")
        app.model.submit(Edit.CreatePlaylist(2, 0, "Aquecimento", false), "p2")
        app.model.submit(Edit.AddToPlaylist(2, ids("Slow Pulse", "Velvet Rail", "Soft Machines")), "p2")
        check(app.model.apply(null) != null) { app.model.banner?.text.orEmpty() }
        // Um cue a mais numa faixa para a tela de cues mostrar algo além das sugestões.
        val t = app.model.view!!.tracks.first { it.title == "Night Signal" }
        val files = app.model.trackFiles(t)
        app.model.submit(Edit.SetCues(t.id, files.cues + Cue(0, 9600)), "cue")
        check(app.model.apply(null) != null)
    }
}

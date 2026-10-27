package os.deckbridge.manager

import java.io.File
import kotlin.test.Test
import org.junit.Assume.assumeTrue
import os.deckbridge.logic.click
import os.deckbridge.logic.encodeWavMono16
import os.deckbridge.manager.importer.ImportPrep
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.transfer.LocalLibrary
import os.deckbridge.manager.ui.DesktopStudio
import os.deckbridge.manager.volume.Backups

/**
 * Quanto custa montar o quadro de um set grande (só com SET_BENCH=1; demora para importar): 40 faixas de 3 min, set de 20
 * com 3 ramos de 5. Mede a leitura da grade (com e sem as ondas) e o cálculo do quadro, frio e com as grades em cache.
 */
class SetBenchTest {
    @Test fun bigSet() {
        assumeTrue("SET_BENCH não definido", System.getenv("SET_BENCH") != null)
        val root = File(TestVolumes.tempDir("dbm-bench"), "library")
        LocalLibrary.ensure(root)
        val m = ManagerModel(Backups(TestVolumes.tempDir("dbm-bench-bk")))
        check(m.open(root))
        val src = TestVolumes.tempDir("dbm-bench-src")
        val keys = listOf("Am", "Em", "Bm", "F#m", "C", "G", "D", "A")
        val edits = (0 until 40).map { n ->
            val f = File(src, "T$n.wav").apply { writeBytes(encodeWavMono16(click(bpm = 118.0 + n % 12, seconds = 180, rate = 22050, freq = 200.0 + n * 9), 22050)) }
            val p = ImportPrep.prepare(f)
            Edit.ImportTrack(p, "T$n", "Demo", "", "House", keys[n % keys.size], 2025, n + 1, p.suggestedCues) to "i$n"
        }
        check(m.submitAll(edits) == 40)
        check(m.apply(null) != null)
        val ids = m.view!!.tracks.map { it.id }
        val pid = m.nextPlaylistId()
        m.submit(Edit.CreatePlaylist(pid, 0, "Grande", false), "p")
        m.submit(Edit.AddToPlaylist(pid, ids.take(20)), "p")
        check(m.apply(null) != null)
        val s = DesktopStudio(m, java.util.prefs.Preferences.userRoot().node("deckbridge-bench/${System.nanoTime()}"), TestVolumes.tempDir("dbm-bench-sets"))
        val t0 = m.view!!.tracks.first()
        fun ms(block: () -> Unit): Long { val a = System.nanoTime(); block(); return (System.nanoTime() - a) / 1_000_000 }
        val full = ms { repeat(20) { m.trackFiles(t0) } } / 20.0
        val light = ms { repeat(20) { m.trackGrid(t0) } } / 20.0
        s.sets.open(pid)
        for (k in 0 until 3) {
            s.sets.focus(pid)
            s.sets.createBranch(ids[3 + k * 5], 5, os.deckbridge.manager.mix.SetShape.FLOW, rejoin = true)
        }
        s.sets.forgetGrids()
        s.sets.focus(pid)
        val cold = ms {
            val layout = s.sets.canvas()!!
            layout.lanes.forEach { s.sets.lanePlan(it.playlistId) }
        }
        val issuesCold = ms { s.sets.issuePairs() }
        val warm = ms {
            val layout = s.sets.canvas()!!
            layout.lanes.forEach { s.sets.lanePlan(it.playlistId) }
        }
        val issuesWarm = ms { s.sets.issuePairs() }
        println("BENCH grade com ondas ${"%.1f".format(full)} ms · só grade ${"%.1f".format(light)} ms por faixa")
        println("BENCH quadro frio $cold ms (avisos de todos os caminhos $issuesCold ms) · com cache $warm ms (avisos $issuesWarm ms) · ${s.sets.canvas()!!.rows} linhas")
    }
}

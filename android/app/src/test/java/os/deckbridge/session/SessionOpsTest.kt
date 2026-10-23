package os.deckbridge.session

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import os.deckbridge.logic.beatPhase

class SessionOpsTest {
    @Test
    fun writeOpsAndCheckAlignment() {
        assumeTrue(SessionAssets.enabled())
        val catalog = SessionAssets.catalog()
        val notes = StringBuilder()
        // Origens reais do bumbo medidas no áudio (não as da análise do app): 0,005 s e 0,0 s.
        val oA = 0.005
        val oB = 0.0
        val checkpoints = doubleArrayOf(24.0, 27.0, 33.0, 41.0, 44.0, 48.0, 57.0, 63.0, 82.0, 88.0, 93.0)
        var nextCheck = 0
        val model = runSession(catalog, 10) { m, frame ->
            val t = frame / 10.0
            if (nextCheck < checkpoints.size && t >= checkpoints[nextCheck]) {
                val effA = m.tracks[0].bpm * m.faces[0].pitch
                val effB = m.tracks[1].bpm * m.faces[1].pitch
                var d = beatPhase(m.pos[1], oB, m.tracks[1].bpm) - beatPhase(m.pos[0], oA, m.tracks[0].bpm)
                if (d > 0.5) d -= 1.0
                if (d < -0.5) d += 1.0
                notes.appendLine("t=%.1f  A pos %.2f play=%s  B pos %.2f play=%s pitchB=%.5f  effBpm A %.3f B %.3f  fase B-A = %+.1f ms".format(
                    t, m.pos[0], m.faces[0].playing, m.pos[1], m.faces[1].playing, m.faces[1].pitch, effA, effB, d * 60.0 / effA * 1000.0))
                nextCheck += 1
            }
        }
        File(SessionAssets.dir, "ops.tsv").writeText(model.finish())
        File(SessionAssets.dir, "checks.txt").writeText(notes.toString())
        println(notes)
    }
}

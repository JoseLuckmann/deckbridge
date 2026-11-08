package os.deckbridge.manager

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import os.deckbridge.manager.mix.SetPlanner
import os.deckbridge.manager.mix.TrackGrid
import os.deckbridge.manager.volume.Backups

/**
 * Confere as transições automáticas numa biblioteca real (fora do repositório): `TRANSITION_CHECK=<pasta da biblioteca>`.
 * Para cada par de faixas, imprime a duração escolhida, por quê, onde sai e onde o drop da que entra cai.
 */
class TransitionCheckTest {
    @Test fun printAutoTransitions() {
        val dir = System.getenv("TRANSITION_CHECK")?.let(::File)
        assumeTrue("sem TRANSITION_CHECK", dir?.isDirectory == true)
        val m = ManagerModel(Backups(TestVolumes.tempDir("tc")))
        check(m.open(dir!!))
        val v = m.view!!
        fun grid(t: os.deckbridge.manager.pdb.TrackItem): TrackGrid {
            val f = m.trackFiles(t)
            return TrackGrid(f.beats, f.cues, f.durationMs)
        }
        for (a in v.tracks) for (b in v.tracks) {
            if (a.id == b.id) continue
            val ga = grid(a)
            val gb = grid(b)
            val (tr, why) = SetPlanner.auto(a, ga, b, gb)
            val drop = gb.cues.firstOrNull { it.comment.equals("Drop", true) }?.timeMs
            val outro = ga.cues.firstOrNull { it.comment.equals("Outro", true) }?.timeMs
            fun mmss(ms: Int?) = ms?.let { "%d:%02d".format(it / 60000, it / 1000 % 60) } ?: "—"
            println(
                "TC\t${a.title.take(22)}\t→\t${b.title.take(22)}\t${tr.bars} comp ($why)\tsai ${mmss(tr.outMs)} (outro ${mmss(outro)}, fim ${mmss(ga.durationMs)})" +
                    "\tentra ${mmss(tr.inMs)}\tdrop ${mmss(drop)}",
            )
        }
    }
}

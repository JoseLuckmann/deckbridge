package os.deckbridge.logic

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RealMusicTest {
    @Test
    fun pipelineOnTheCcCorpus() {
        assumeTrue(java.io.File("/opt/cursor/artifacts/real-corpus").isDirectory)
        val ready = RealCorpus.prepare()
        assertTrue(ready != null)
        val text = ready!!.report
        assertTrue(text.contains("faixas analisadas:"))
        assertTrue(text.contains("export deste app"))
        assertTrue(text.contains("PWV5:"))
        assertTrue(ready.catalog.tracks.size >= 6)
        assertTrue(ready.shots.size >= 3)
        assertTrue(text.contains("seam_dry_energy") || text.contains("mix não compilou"))
    }
}

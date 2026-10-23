package os.deckbridge.session

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SessionAnalysisTest {
    @Test
    fun analyseTheTwoTracks() {
        assumeTrue(SessionAssets.enabled())
        val started = System.currentTimeMillis()
        val catalog = SessionAssets.catalog()
        val out = StringBuilder()
        catalog.tracks.forEach { track ->
            val analysis = catalog.analyses[track.id]
            val first = analysis?.beats?.take(5)?.joinToString { "${it.timeMs}ms/${it.beatInBar}" }
            analysis?.hotCues?.forEach { out.appendLine("   hot ${it.index} ${it.timeMs} ms") }
            analysis?.memoryCues?.forEach { out.appendLine("   mem ${it.timeMs} ms") }
            out.appendLine("${track.id} ${track.title} bpm=${track.bpm} key=${track.keyName} dur=${track.durationSeconds}s beats=${analysis?.beats?.size} first=[$first] mainCueMs=${analysis?.mainCueMs} hot=${analysis?.hotCues?.size} wave=${analysis?.waveSource}")
        }
        out.appendLine("analysis took ${(System.currentTimeMillis() - started) / 1000} s")
        File(SessionAssets.dir, "analysis.txt").writeText(out.toString())
        println(out)
    }
}

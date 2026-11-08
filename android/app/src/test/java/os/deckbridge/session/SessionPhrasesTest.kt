package os.deckbridge.session

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Lista as frases das faixas da sessão (SESSION_DIR, fora do git) para conferir de ouvido. */
class SessionPhrasesTest {
    @Test
    fun listPhrases() {
        assumeTrue(SessionAssets.enabled() && System.getenv("SESSION_PHRASES") == "1")
        val catalog = SessionAssets.catalog()
        val out = StringBuilder()
        for (track in catalog.tracks.sortedBy { it.title }) {
            val analysis = catalog.analyses[track.id] ?: continue
            out.append("${track.title}  %.2f BPM  %d:%02d\n".format(track.bpm, track.durationSeconds / 60, track.durationSeconds % 60))
            for (phrase in analysis.phrases) {
                val start = phrase.startMs / 1000
                val end = phrase.endMs / 1000
                val bars = (phrase.endMs - phrase.startMs) / 1000.0 / (240.0 / track.bpm)
                val fill = phrase.fillMs?.let { "  fill %d:%02d".format(it / 60000, (it / 1000) % 60) } ?: ""
                out.append("  %-7s %d:%02d – %d:%02d  (%.0f compassos)%s\n".format(phrase.kind, start / 60, start % 60, end / 60, end % 60, bars, fill))
            }
        }
        File(SessionAssets.dir, "phrases.txt").writeText(out.toString())
    }
}
